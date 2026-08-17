[CmdletBinding()]
param(
    [switch]$Https,
    [switch]$NoBuild,
    [switch]$Components,
    [switch]$Help
)

if ($Help) {
    Write-Host 'Usage: pwsh -File scripts/start.ps1 [-Https] [-NoBuild] [-Components]'
    Write-Host '  -Https   Enable Secure cookies; use only behind the HTTPS proxy.'
    Write-Host '  -NoBuild Start existing images without rebuilding.'
    Write-Host '  -Components Start only PostgreSQL/pgvector and Nginx; application code stays on the host.'
    exit 0
}

$ErrorActionPreference = 'Stop'
$rootDir = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $rootDir '.env.production'
$composeFile = Join-Path $rootDir 'compose.prod.yaml'

function New-HexToken([int]$ByteCount) {
    $bytes = New-Object byte[] $ByteCount
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
    return ([System.BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
}

function Invoke-Docker([string[]]$Arguments, [switch]$Quiet) {
    if ($Quiet) { & docker @Arguments *> $null } else { & docker @Arguments }
    if ($LASTEXITCODE -ne 0) { throw "docker $($Arguments -join ' ') failed with exit code $LASTEXITCODE" }
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Docker Desktop with Docker Compose v2 is required.'
}
Invoke-Docker @('compose', 'version') -Quiet
& docker info *> $null
if ($LASTEXITCODE -ne 0) {
    $dockerDesktop = Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'
    if (-not (Test-Path -LiteralPath $dockerDesktop)) {
        throw 'Docker daemon is not running and Docker Desktop could not be found.'
    }
    Write-Host 'Starting Docker Desktop...'
    Start-Process -FilePath $dockerDesktop -WindowStyle Hidden
    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        Start-Sleep -Seconds 2
        & docker info *> $null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
    }
    if (-not $ready) { throw 'Docker Desktop did not become ready within 120 seconds.' }
}

Set-Location $rootDir

if ($Components) {
    if ($Https) { throw '-Https is not supported in component mode; configure HTTPS in an external proxy.' }

    $componentEnvFile = Join-Path $rootDir '.env.components'
    $componentComposeFile = Join-Path $rootDir 'compose.components.yaml'
    $frontendDist = Join-Path $rootDir 'frontend\dist'
    New-Item -ItemType Directory -Force -Path $frontendDist | Out-Null

    if (-not (Test-Path -LiteralPath $componentEnvFile)) {
        $postgresPassword = 'Db' + (New-HexToken 24)
        $frontendDistPath = $frontendDist.Replace('\', '/')
        $componentConfiguration = @"
POSTGRES_DB=codebase_kb
POSTGRES_USER=codebase_kb
POSTGRES_PASSWORD=$postgresPassword
POSTGRES_PORT=5432
APP_HTTP_BIND_ADDRESS=127.0.0.1
APP_HTTP_PORT=8088
APP_FRONTEND_DIST_HOST_ROOT="$frontendDistPath"
TZ=Asia/Shanghai
"@
        [System.IO.File]::WriteAllText($componentEnvFile, $componentConfiguration, [System.Text.UTF8Encoding]::new($false))
        Write-Host "Created $componentEnvFile"
        Write-Host "PostgreSQL password: $postgresPassword"
        Write-Host 'Save this password for the host backend configuration.' -ForegroundColor Yellow
    }

    if (Select-String -LiteralPath $componentEnvFile -SimpleMatch 'replace-with' -Quiet) {
        throw "$componentEnvFile still contains placeholder secrets; replace them before starting."
    }

    $componentCompose = @('compose', '--env-file', $componentEnvFile, '-f', $componentComposeFile)
    Invoke-Docker ($componentCompose + @('config', '--quiet'))
    Invoke-Docker ($componentCompose + @('up', '-d'))

    $postgresHealthy = $false
    $nginxHealthy = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        & docker @($componentCompose + @('exec', '-T', 'postgres', 'sh', '-c', 'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"')) *> $null
        if ($LASTEXITCODE -eq 0) { $postgresHealthy = $true }
        & docker @($componentCompose + @('exec', '-T', 'nginx', 'wget', '--quiet', '--output-document=-', 'http://127.0.0.1:8080/component-health')) *> $null
        if ($LASTEXITCODE -eq 0) { $nginxHealthy = $true }
        if ($postgresHealthy -and $nginxHealthy) { break }
        Start-Sleep -Seconds 2
    }
    if (-not $postgresHealthy -or -not $nginxHealthy) {
        & docker @($componentCompose + @('logs', '--tail=120'))
        throw 'Component services did not become healthy in time.'
    }

    & docker @($componentCompose + @('ps'))
    $componentLines = Get-Content -LiteralPath $componentEnvFile
    $httpPortLine = $componentLines | Where-Object { $_ -like 'APP_HTTP_PORT=*' } | Select-Object -Last 1
    $postgresPortLine = $componentLines | Where-Object { $_ -like 'POSTGRES_PORT=*' } | Select-Object -Last 1
    $httpPort = if ($httpPortLine) { $httpPortLine.Substring('APP_HTTP_PORT='.Length) } else { '8088' }
    $postgresPort = if ($postgresPortLine) { $postgresPortLine.Substring('POSTGRES_PORT='.Length) } else { '5432' }
    Write-Host "Components are ready: Nginx http://127.0.0.1:$httpPort, PostgreSQL 127.0.0.1:$postgresPort" -ForegroundColor Green
    Write-Host "Frontend assets: $frontendDist (run npm build on the host)"
    Write-Host 'Backend API: Nginx expects the host backend at http://host.docker.internal:8080'
    Write-Host 'Logs: docker compose --env-file .env.components -f compose.components.yaml logs -f'
    Write-Host 'Stop: docker compose --env-file .env.components -f compose.components.yaml down'
    exit 0
}

$repositoryDir = Join-Path $rootDir 'runtime\repositories'
$dataDir = Join-Path $rootDir 'runtime\data'
New-Item -ItemType Directory -Force -Path $repositoryDir, $dataDir | Out-Null

if (-not (Test-Path -LiteralPath $envFile)) {
    $postgresPassword = 'Db' + (New-HexToken 24)
    $adminPassword = 'Ac!' + (New-HexToken 12) + '9z'
    $llmMasterKey = New-HexToken 32
    $credentialMasterKey = New-HexToken 32
    $secureCookie = if ($Https) { 'true' } else { 'false' }
    $repositoryPath = $repositoryDir.Replace('\', '/')
    $dataPath = $dataDir.Replace('\', '/')
    $configuration = @"
POSTGRES_DB=codebase_kb
POSTGRES_USER=codebase_kb
POSTGRES_PASSWORD=$postgresPassword
APP_INITIAL_ADMIN_USERNAME=admin
APP_INITIAL_ADMIN_PASSWORD=$adminPassword
APP_LLM_MASTER_KEY=$llmMasterKey
APP_CREDENTIAL_MASTER_KEY=$credentialMasterKey
APP_REPOSITORY_HOST_ROOT="$repositoryPath"
APP_MANAGED_DATA_HOST_ROOT="$dataPath"
APP_RUNTIME_UID=10001
APP_RUNTIME_GID=10001
APP_HTTP_BIND_ADDRESS=127.0.0.1
APP_HTTP_PORT=8088
APP_SESSION_COOKIE_SECURE=$secureCookie
APP_LLM_ALLOW_INSECURE_LOCAL=false
CODEGRAPH_VERSION=1.5.0
TZ=Asia/Shanghai
"@
    [System.IO.File]::WriteAllText($envFile, $configuration, [System.Text.UTF8Encoding]::new($false))
    Write-Host "Created $envFile"
    Write-Host 'Initial administrator: admin'
    Write-Host "Initial password: $adminPassword"
    Write-Host 'Save this password now. The first login requires a password change.' -ForegroundColor Yellow
}

if (Select-String -LiteralPath $envFile -SimpleMatch 'replace-with' -Quiet) {
    throw "$envFile still contains placeholder secrets; replace them before starting."
}
if ($Https) { $env:APP_SESSION_COOKIE_SECURE = 'true' }

$compose = @('compose', '--env-file', $envFile, '-f', $composeFile)
Invoke-Docker ($compose + @('config', '--quiet'))
if ($NoBuild) {
    Invoke-Docker ($compose + @('up', '-d'))
} else {
    Invoke-Docker ($compose + @('up', '-d', '--build'))
}

$healthy = $false
for ($attempt = 0; $attempt -lt 90; $attempt++) {
    & docker @($compose + @('exec', '-T', 'backend', 'curl', '--fail', '--silent', 'http://127.0.0.1:8080/actuator/health')) *> $null
    if ($LASTEXITCODE -eq 0) { $healthy = $true; break }
    Start-Sleep -Seconds 2
}
if (-not $healthy) {
    & docker @($compose + @('logs', '--tail=120', 'backend'))
    throw 'Backend did not become healthy in time.'
}

& docker @($compose + @('ps'))
$portLine = Get-Content -LiteralPath $envFile | Where-Object { $_ -like 'APP_HTTP_PORT=*' } | Select-Object -Last 1
$httpPort = if ($portLine) { $portLine.Substring('APP_HTTP_PORT='.Length) } else { '8088' }
Write-Host "Analyzer Coder is ready at http://127.0.0.1:$httpPort" -ForegroundColor Green
if (-not $Https -and -not (Select-String -LiteralPath $envFile -Pattern '^APP_SESSION_COOKIE_SECURE=true$' -Quiet)) {
    Write-Host 'HTTP bootstrap mode is active. Before external access, configure deploy/nginx-compose-edge.conf and restart with -Https.' -ForegroundColor Yellow
}
Write-Host 'Logs: docker compose --env-file .env.production -f compose.prod.yaml logs -f'
Write-Host 'Stop: docker compose --env-file .env.production -f compose.prod.yaml down'
