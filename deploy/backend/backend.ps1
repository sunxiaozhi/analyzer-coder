#Requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('start', 'stop', 'restart', 'status', 'help')]
    [string]$Action = 'help',
    [ValidateRange(1, 65535)][int]$Port = 18082,
    [ValidateRange(1, 3600)][int]$TimeoutSeconds = 180,
    [string]$JavaXms = '256m',
    [string]$JavaXmx = '768m'
)
$ErrorActionPreference = 'Stop'
if ($Action -eq 'help') {
    Write-Host 'Usage: pwsh -File backend.ps1 start|stop|restart|status [-Port 18082] [-TimeoutSeconds 180]'
    Write-Host 'Port must match config/application.yml. JVM: -JavaXms 256m -JavaXmx 768m.'
    exit 0
}
$backendRoot = $PSScriptRoot
$jarPath = Join-Path $backendRoot 'app.jar'
$pidPath = Join-Path $backendRoot 'run/backend.pid'
$healthUrl = "http://127.0.0.1:$Port/actuator/health"
$controlLock = $null

function Get-ManagedBackend {
    if (-not (Test-Path -LiteralPath $pidPath)) { return $null }
    $identity = Get-Content -LiteralPath $pidPath -Raw | ConvertFrom-Json
    $backendProcessId = 0
    if (-not [int]::TryParse([string]$identity.id, [ref]$backendProcessId) -or $backendProcessId -le 0) {
        throw 'Invalid PID file; refusing to act.'
    }
    $proc = Get-CimInstance Win32_Process -Filter "ProcessId = $backendProcessId"
    if ($null -eq $proc) { return $null }
    $created = $proc.CreationDate.ToUniversalTime().ToString('o')
    $jarPattern = '-jar\s+"' + [regex]::Escape($jarPath) + '"(?:\s|$)'
    # PowerShell 7.5+ may deserialize ISO timestamps as DateTime, older versions as strings.
    $expectedCreated = if ($identity.created -is [DateTime]) {
        $identity.created.ToUniversalTime().ToString('o')
    } else { [string]$identity.created }
    if ($created -ne $expectedCreated -or $proc.CommandLine -notmatch $jarPattern) {
        throw 'PID no longer belongs to this app.jar; refusing to act on another process.'
    }
    return $proc
}
function Test-BackendHealth {
    try {
        $response = Invoke-RestMethod -Uri $healthUrl -TimeoutSec 3 -NoProxy
        return $response.status -eq 'UP'
    } catch { return $false }
}
function Stop-Backend {
    $proc = Get-ManagedBackend
    if ($null -eq $proc) {
        Remove-Item -LiteralPath $pidPath -Force -ErrorAction SilentlyContinue
        Write-Host 'Backend is stopped.'
        return
    }
    Stop-Process -Id $proc.ProcessId -ErrorAction Stop
    Wait-Process -Id $proc.ProcessId -Timeout 30 -ErrorAction SilentlyContinue
    if ($null -ne (Get-ManagedBackend)) { throw 'Backend did not stop; PID retained.' }
    Remove-Item -LiteralPath $pidPath -Force
    Write-Host 'Backend stopped.'
}
function Start-Backend {
    foreach ($tool in @('java', 'git')) {
        if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { throw "Missing command: $tool" }
    }
    $existing = Get-ManagedBackend
    if ($null -ne $existing) {
        if (Test-BackendHealth) { Write-Host "Backend is already healthy (PID $($existing.ProcessId))."; return }
        throw 'Backend is running but unhealthy. Check logs before restarting.'
    }
    $configPath = Join-Path $backendRoot 'config/application.yml'
    if (-not (Test-Path -LiteralPath $jarPath) -or -not (Test-Path -LiteralPath $configPath)) {
        throw 'Missing app.jar or config/application.yml.'
    }
    $configLines = Get-Content -LiteralPath $configPath | Where-Object { $_ -notmatch '^\s*#' }
    if ($configLines -match 'replace-with') { throw 'Fill in config/application.yml before starting.' }
    $client = [Net.Sockets.TcpClient]::new()
    try {
        $connect = $client.ConnectAsync('127.0.0.1', $Port)
        try { $null = $connect.Wait(1000) } catch {}
        if ($client.Connected) { throw "Port $Port is already occupied; refusing to launch." }
    } finally { $client.Dispose() }
    foreach ($folder in @('data', 'repositories')) {
        [IO.Directory]::CreateDirectory((Join-Path $backendRoot $folder)) | Out-Null
    }
    foreach ($name in @('console', 'stderr')) {
        $logPath = Join-Path $backendRoot "logs/$name.log"
        if (Test-Path -LiteralPath $logPath) {
            Move-Item -LiteralPath $logPath -Destination (Join-Path $backendRoot "logs/$name.previous.log") -Force
        }
    }
    $javaExe = (Get-Command java -CommandType Application | Select-Object -First 1).Source
    $startOptions = @{
        FilePath = $javaExe
        ArgumentList = @("-Xms$JavaXms", "-Xmx$JavaXmx", '-jar', ('"' + $jarPath + '"'))
        WorkingDirectory = $backendRoot
        WindowStyle = 'Hidden'
        PassThru = $true
        RedirectStandardOutput = Join-Path $backendRoot 'logs/console.log'
        RedirectStandardError = Join-Path $backendRoot 'logs/stderr.log'
    }
    $process = Start-Process @startOptions
    try {
        $proc = Get-CimInstance Win32_Process -Filter "ProcessId = $($process.Id)"
        if ($null -eq $proc) { throw 'Java exited immediately. See logs/stderr.log.' }
        @{ id = $process.Id; created = $proc.CreationDate.ToUniversalTime().ToString('o') } |
            ConvertTo-Json | Set-Content -LiteralPath $pidPath -Encoding utf8
        $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
        while ([DateTime]::UtcNow -lt $deadline) {
            if ($null -eq (Get-ManagedBackend)) { break }
            if ((Test-BackendHealth) -and $null -ne (Get-ManagedBackend)) {
                Write-Host "Backend ready: $healthUrl (PID $($process.Id))"
                return
            }
            Start-Sleep -Seconds 2
        }
        throw 'Startup failed. See logs/console.log, logs/stderr.log and logs/backend.log.'
    } catch {
        if (Test-Path -LiteralPath $pidPath) { Stop-Backend }
        elseif (-not $process.HasExited) { $process.Kill() }
        throw
    } finally { $process.Dispose() }
}

try {
    foreach ($folder in @('run', 'logs')) {
        [IO.Directory]::CreateDirectory((Join-Path $backendRoot $folder)) | Out-Null
    }
    $lockPath = Join-Path $backendRoot 'run/control.lock'
    $controlLock = [IO.File]::Open($lockPath, 'OpenOrCreate', 'ReadWrite', 'None')
    switch ($Action) {
        'start' { Start-Backend }
        'stop' { Stop-Backend }
        'restart' { Stop-Backend; Start-Backend }
        'status' {
            $proc = Get-ManagedBackend
            if ($null -eq $proc) { throw 'Backend is stopped.' }
            Write-Host "Backend PID: $($proc.ProcessId)"
            if (-not (Test-BackendHealth)) { throw 'Health: unavailable' }
            Write-Host 'Health: UP'
        }
    }
} catch {
    Write-Error $_
    exit 1
} finally {
    if ($null -ne $controlLock) { $controlLock.Dispose() }
}
