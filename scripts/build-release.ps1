#Requires -Version 7.0
[CmdletBinding()]
param(
    [string]$Version = (Get-Date -Format 'yyyyMMdd-HHmmss'),
    [string]$OutputDirectory = 'release',
    [ValidateSet('linux/amd64', 'linux/arm64')][string]$Platform = 'linux/amd64',
    [switch]$WithoutImages,
    [switch]$SkipBuild,
    [switch]$Help
)
$ErrorActionPreference = 'Stop'
$entry = Join-Path (Split-Path -Parent $PSScriptRoot) 'scripts/build-release.mjs'
if ($Help) { & node $entry --help; exit $LASTEXITCODE }
$arguments = @($entry, '--version', $Version, '--output', $OutputDirectory, '--platform', $Platform)
if ($WithoutImages) { $arguments += '--without-images' }
if ($SkipBuild) { $arguments += '--skip-build' }
& node @arguments
exit $LASTEXITCODE
