[CmdletBinding()]
param(
    [string]$Version = (Get-Date -Format 'yyyyMMdd-HHmmss'),
    [string]$OutputDirectory = 'release',
    [ValidateSet('linux/amd64', 'linux/arm64')]
    [string]$Platform = 'linux/amd64'
)

$ErrorActionPreference = 'Stop'
$rootDir = Split-Path -Parent $PSScriptRoot

if ($Version -notmatch '^[A-Za-z0-9._-]+$') {
    throw 'Version may contain only letters, digits, dot, underscore, and hyphen.'
}
if (-not [System.IO.Path]::IsPathRooted($OutputDirectory)) {
    $OutputDirectory = Join-Path $rootDir $OutputDirectory
}
$outputRoot = [System.IO.Path]::GetFullPath($OutputDirectory)
$packageName = "analyzer-coder-components-offline-$Version"
$packageDir = Join-Path $outputRoot $packageName
$archivePath = Join-Path $outputRoot "$packageName.tar.gz"

function Invoke-Docker([string[]]$Arguments) {
    & docker @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker $($Arguments -join ' ') failed with exit code $LASTEXITCODE"
    }
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Docker Desktop is required.'
}
if (-not (Get-Command tar -ErrorAction SilentlyContinue)) {
    throw 'tar is required to create the final archive.'
}
& docker info *> $null
if ($LASTEXITCODE -ne 0) {
    $dockerDesktop = Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'
    if (-not (Test-Path -LiteralPath $dockerDesktop)) {
        throw 'Docker daemon is not running and Docker Desktop could not be found.'
    }
    Write-Host 'Starting Docker Desktop...'
    Start-Process -FilePath $dockerDesktop -WindowStyle Hidden
    $dockerReady = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        Start-Sleep -Seconds 2
        & docker info *> $null
        if ($LASTEXITCODE -eq 0) {
            $dockerReady = $true
            break
        }
    }
    if (-not $dockerReady) { throw 'Docker Desktop did not become ready within 120 seconds.' }
}
if ((Test-Path -LiteralPath $packageDir) -or (Test-Path -LiteralPath $archivePath)) {
    throw "Output already exists: $packageDir or $archivePath"
}

Push-Location $rootDir
try {
    Write-Host "Fetching PostgreSQL/pgvector image for $Platform..."
    Invoke-Docker @('pull', '--platform', $Platform, 'pgvector/pgvector:pg17')
    Invoke-Docker @('tag', 'pgvector/pgvector:pg17', 'analyzer-coder/postgres:offline')

    Write-Host "Fetching Nginx image for $Platform..."
    Invoke-Docker @('pull', '--platform', $Platform, 'nginx:1.27-alpine')
    Invoke-Docker @('tag', 'nginx:1.27-alpine', 'analyzer-coder/nginx:offline')

    New-Item -ItemType Directory -Path $packageDir -Force | Out-Null
    Copy-Item -LiteralPath 'scripts\offline-install.sh' -Destination (Join-Path $packageDir 'install.sh')
    Copy-Item -LiteralPath 'scripts\offline-install.ps1' -Destination (Join-Path $packageDir 'install.ps1')
    Copy-Item -LiteralPath 'deploy\OFFLINE-README.md' -Destination (Join-Path $packageDir 'README.md')
    Copy-Item -LiteralPath 'docs\08-linux-git-deployment.md' -Destination (Join-Path $packageDir 'STARTUP-GUIDE.md')

    $manifest = @"
代码知识平台离线包
Version: $Version
Created: $([DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ'))
Platform: $Platform
Images:
  analyzer-coder/postgres:offline
  analyzer-coder/nginx:offline
"@
    [System.IO.File]::WriteAllText(
        (Join-Path $packageDir 'MANIFEST.txt'),
        $manifest,
        [System.Text.UTF8Encoding]::new($false)
    )

    Write-Host 'Exporting Docker images...'
    Invoke-Docker @(
        'save', '--output', (Join-Path $packageDir 'images.tar'),
        'analyzer-coder/postgres:offline',
        'analyzer-coder/nginx:offline'
    )

    $imageHash = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $packageDir 'images.tar')).Hash.ToLowerInvariant()
    [System.IO.File]::WriteAllText(
        (Join-Path $packageDir 'SHA256SUMS'),
        "$imageHash  images.tar`n",
        [System.Text.UTF8Encoding]::new($false)
    )

    Push-Location $outputRoot
    try {
        & tar -czf $archivePath $packageName
        if ($LASTEXITCODE -ne 0) { throw 'tar failed to create the final archive.' }
    } finally {
        Pop-Location
    }
} finally {
    Pop-Location
}

Write-Host "Offline package created: $archivePath" -ForegroundColor Green
