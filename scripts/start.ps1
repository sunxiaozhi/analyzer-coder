[CmdletBinding()]
param(
    [switch]$Https,
    [switch]$NoBuild,
    [switch]$Help
)

if ($Help) {
    Write-Host 'Usage: pwsh -File scripts/start.ps1 [-Https] [-NoBuild]'
    Write-Host '  -Https   Enable Secure cookies; use only behind the HTTPS proxy.'
    Write-Host '  -NoBuild Start existing images without rebuilding.'
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
