#Requires -Version 7.0
[CmdletBinding()]
param(
    [string]$Version,
    [string]$OutputDirectory = 'release',
    [ValidateSet('linux/amd64', 'linux/arm64')][string]$Platform = 'linux/amd64',
    [string]$RuntimeImage
)
$ErrorActionPreference = 'Stop'
$arguments = @((Join-Path $PSScriptRoot 'build-docker-image.mjs'), '--output', $OutputDirectory, '--platform', $Platform)
if ($Version) { $arguments += @('--version', $Version) }
if ($RuntimeImage) { $arguments += @('--runtime-image', $RuntimeImage) }
& node @arguments
exit $LASTEXITCODE
