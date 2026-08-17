[CmdletBinding()]
param(
    [switch]$Help
)

if ($Help) {
    Write-Host 'Usage: pwsh -File install.ps1'
    Write-Host 'Verify and import the PostgreSQL/pgvector and Nginx images.'
    exit 0
}

$ErrorActionPreference = 'Stop'
$rootDir = $PSScriptRoot

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Docker Desktop with Docker Compose v2 is required.'
}
& docker compose version *> $null
if ($LASTEXITCODE -ne 0) { throw 'Docker Compose v2 is required.' }
& docker info *> $null
if ($LASTEXITCODE -ne 0) { throw 'Docker daemon is not running or is not accessible.' }

$checksumLine = (Get-Content -LiteralPath (Join-Path $rootDir 'SHA256SUMS') -Raw).Trim()
if ($checksumLine -notmatch '^([0-9a-fA-F]{64})\s+\*?images\.tar$') {
    throw 'SHA256SUMS has an invalid format.'
}
$expectedHash = $Matches[1].ToLowerInvariant()
$actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $rootDir 'images.tar')).Hash.ToLowerInvariant()
if ($actualHash -ne $expectedHash) { throw 'images.tar checksum verification failed.' }
Write-Host 'images.tar: checksum OK' -ForegroundColor Green

Write-Host 'Importing offline Docker images...'
& docker load --input (Join-Path $rootDir 'images.tar')
if ($LASTEXITCODE -ne 0) { throw 'docker load failed.' }
Write-Host 'Images imported. Clone the application repository and run: bash scripts/start.sh' -ForegroundColor Green
