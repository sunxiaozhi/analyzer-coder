#Requires -Version 7.0
[CmdletBinding()]
param(
    [string]$Version = '1.0.0',
    [string]$OutputDirectory = 'release',
    [ValidateSet('linux/amd64', 'linux/arm64')][string]$Platform = 'linux/amd64',
    [string]$RuntimeImage
)
$ErrorActionPreference = 'Stop'
$arguments = @((Join-Path $PSScriptRoot 'build-docker-image.mjs'), '--version', $Version, '--output', $OutputDirectory, '--platform', $Platform)
if ($RuntimeImage) { $arguments += @('--runtime-image', $RuntimeImage) }
& node @arguments
exit $LASTEXITCODE
