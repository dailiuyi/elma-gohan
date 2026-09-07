[CmdletBinding()]
param(
    [ValidateRange(1, 366)]
    [int]$Days = 30,

    [ValidateRange(0, 65535)]
    [int]$Port = 0
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$python = Get-Command py.exe -ErrorAction SilentlyContinue
if ($null -eq $python) {
    $python = Get-Command python.exe -ErrorAction Stop
}

$server = Join-Path $PSScriptRoot 'serve_dashboard.py'
& $python.Source $server --days $Days --port $Port --open
exit $LASTEXITCODE
