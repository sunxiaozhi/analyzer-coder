#Requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('init','start','stop','restart','status','logs','backup','upgrade','help')]
    [string]$Action = 'start',
    [string]$PackageDirectory
)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath($PSScriptRoot)
$env:ANALYZER_INSTALLATION_ROOT = $root

function Invoke-Docker {
    & docker @args
    if ($LASTEXITCODE -ne 0) { throw "Docker command failed (exit $LASTEXITCODE): $($args[0])" }
}
function Invoke-Compose {
    Invoke-Docker compose --project-directory $root --env-file (Join-Path $root '.env') -f (Join-Path $root 'compose.yaml') @args
}
function Get-Image([string]$File) {
    $line = @(Get-Content -LiteralPath $File | Where-Object { $_ -match '^ANALYZER_IMAGE=' })
    if ($line.Count -ne 1) { throw "Invalid or missing ANALYZER_IMAGE in $File" }
    $image = $line[0].Substring('ANALYZER_IMAGE='.Length).Trim()
    if ($image -notmatch '^analyzer-coder:[A-Za-z0-9][A-Za-z0-9_.-]{0,79}$') { throw 'Invalid image tag' }
    return $image
}
function Import-Package([string]$Directory, [string]$Image) {
    if (Test-Path -LiteralPath (Join-Path $Directory '.incomplete')) { throw 'Expected a complete extracted release directory.' }
    $required = @('analyzer.sh','analyzer.ps1','compose.yaml','image.env','README.md','MANIFEST.json','image.tar')
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($line in Get-Content -LiteralPath (Join-Path $Directory 'SHA256SUMS')) {
        if ($line -notmatch '^([0-9a-f]{64})  ([^\r\n]+)$') { throw 'Invalid checksum line' }
        $expected = $Matches[1]
        $relative = $Matches[2]
        if ($relative -cnotin $required -or -not $seen.Add($relative)) { throw "Invalid or duplicate checksum: $relative" }
        $file = [IO.Path]::GetFullPath((Join-Path $Directory $relative))
        if (-not $file.StartsWith($Directory.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Checksum path escapes package' }
        if ((Get-FileHash -Algorithm SHA256 -LiteralPath $file).Hash.ToLowerInvariant() -ne $expected) { throw "Checksum mismatch: $relative" }
    }
    if ($seen.Count -ne $required.Count) { throw 'Missing delivery file checksums' }
    Invoke-Docker load -i (Join-Path $Directory 'image.tar')
    Invoke-Docker image inspect $Image --format '{{.Os}}/{{.Architecture}}'
}
function Initialize-Config([string]$Image, [switch]$Upgrade) {
    $arguments = @('run','--rm','--entrypoint','node','-v',"${root}:/installation",$Image,'/opt/analyzer-coder/deploy/configure.mjs','/installation',$Image)
    if ($Upgrade) { $arguments += '--upgrade' }
    Invoke-Docker @arguments
}
function Assert-Owner {
    $container = Invoke-Compose ps --all --quiet analyzer
    if ($container) {
        $owner = Invoke-Docker inspect $container --format '{{index .Config.Labels "com.analyzer-coder.installation"}}'
        if ($owner -ne $root) { throw 'This Compose project belongs to another installation. Set a unique COMPOSE_PROJECT_NAME in .env, or migrate the old deployment first.' }
    }
}
function Prepare {
    $envFile = Join-Path $root '.env'
    $image = if (Test-Path -LiteralPath $envFile) { Get-Image $envFile } else { Get-Image (Join-Path $root 'image.env') }
    & docker image inspect $image *> $null
    if ($LASTEXITCODE -ne 0) { Import-Package $root $image }
    Initialize-Config $image
    Invoke-Compose config --quiet
    Assert-Owner
}
function Start-Application {
    try { Invoke-Compose up -d --pull never --wait --wait-timeout 240 }
    catch {
        Invoke-Compose logs --tail 100 analyzer | Out-Host
        throw 'Startup failed. Data and configuration were retained; inspect the logs before retrying.'
    }
    Invoke-Compose ps
    Write-Host "Ready. Port/admin login: $root/.env; data: $root/data; config: $root/config"
}
function Backup-Application {
    $image = Get-Image (Join-Path $root '.env')
    $container = Invoke-Compose ps --quiet analyzer
    $wasRunning = $container -and ((Invoke-Docker inspect $container --format '{{.State.Running}}') -eq 'true')
    Invoke-Compose stop analyzer | Out-Host
    $backupDirectory = Join-Path $root 'backups'
    New-Item -ItemType Directory -Force -Path $backupDirectory | Out-Null
    $name = 'analyzer-' + [DateTimeOffset]::UtcNow.ToString('yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8) + '.tar.gz'
    try {
        Invoke-Docker run --rm --entrypoint sh -v "${root}:/installation:ro" -v "${backupDirectory}:/backup" $image -c 'umask 077; tar -czf "/backup/$1" -C /installation data config .env' sh $name | Out-Host
    } finally {
        if ($wasRunning) { Invoke-Compose start --wait --wait-timeout 240 analyzer | Out-Host }
    }
    Write-Host "Backup: $(Join-Path $backupDirectory $name)"
}
function Upgrade-Application {
    if (-not $PackageDirectory) { throw 'Usage: ./analyzer.ps1 upgrade -PackageDirectory C:/path/to/extracted-new-release' }
    $package = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $PackageDirectory).Path)
    if ($package -eq $root) { throw 'Extract the new package in another directory first.' }
    $image = Get-Image (Join-Path $package 'image.env')
    Import-Package $package $image | Out-Host
    Initialize-Config $image -Upgrade | Out-Host
    foreach ($name in @('analyzer.sh','analyzer.ps1','compose.yaml','image.env','README.md','MANIFEST.json','SHA256SUMS','image.tar')) {
        Copy-Item -LiteralPath (Join-Path $package $name) -Destination (Join-Path $root "$name.new")
        Move-Item -LiteralPath (Join-Path $root "$name.new") -Destination (Join-Path $root $name) -Force
    }
    Invoke-Compose config --quiet
    Start-Application
}
try {
    if ($Action -eq 'help') { Write-Host 'Usage: ./analyzer.ps1 init|start|stop|restart|status|logs|backup|upgrade [-PackageDirectory NEW_PACKAGE_DIRECTORY]'; exit 0 }
    if ((Invoke-Docker info --format '{{.OSType}}') -ne 'linux') { throw 'Docker must use Linux containers.' }
    Invoke-Docker compose version | Out-Null
    Prepare | Out-Host
    switch ($Action) {
        init { Write-Host "Configuration ready: $root/.env and $root/config" }
        start { Start-Application }
        stop { Invoke-Compose stop analyzer }
        restart { Invoke-Compose up -d --force-recreate --pull never --wait --wait-timeout 240; Invoke-Compose ps }
        status { Invoke-Compose ps --all }
        logs { Invoke-Compose logs --follow --tail 100 analyzer }
        backup { Backup-Application }
        upgrade { Upgrade-Application }
    }
} catch { Write-Error $_; exit 1 }
