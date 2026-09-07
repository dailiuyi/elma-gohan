[CmdletBinding()]
param(
  [string]$SshHost = 'elma-gohan',
  [string]$NodePath = 'node',
  [switch]$SkipBuild
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$consoleRoot = Split-Path -Parent $PSScriptRoot
$repoRoot = Split-Path -Parent (Split-Path -Parent $consoleRoot)
if (-not $SkipBuild) {
  $nodeVersion = & $NodePath --version
  if ($nodeVersion -notmatch '^v22\.') { throw 'Use Node 22; pass -NodePath with its node.exe path.' }
  & $NodePath (Join-Path $repoRoot 'node_modules/vite/bin/vite.js') build --config (Join-Path $consoleRoot 'vite.config.ts')
  if ($LASTEXITCODE -ne 0) { throw 'Console build failed.' }
}
$releaseId = 'elma-admin-' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
$work = Join-Path $consoleRoot '.deploy'
New-Item -ItemType Directory -Path $work -Force | Out-Null
$archive = Join-Path $work ($releaseId + '.tar.gz')
& python (Join-Path $PSScriptRoot 'package_release.py') --output $archive
if ($LASTEXITCODE -ne 0) { throw 'Release packaging failed.' }
$checksum = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
$sshOptions = @('-o','BatchMode=yes','-o','ConnectTimeout=15')
& scp @sshOptions $archive "${SshHost}:/tmp/$releaseId.tar.gz"
if ($LASTEXITCODE -ne 0) { throw 'Release upload failed.' }
$remote = "set -e; test `"`$(sha256sum /tmp/$releaseId.tar.gz | cut -d ' ' -f 1)`" = '$checksum'; install -d -m 0700 /tmp/$releaseId; tar -xzf /tmp/$releaseId.tar.gz -C /tmp/$releaseId; bash /tmp/$releaseId/output/admin-console/deploy/install.sh /tmp/$releaseId"
& ssh @sshOptions $SshHost $remote
if ($LASTEXITCODE -ne 0) { throw 'Console installation failed. Review remote rollback output.' }
Write-Output 'CONSOLE_RELEASE_INSTALLED https://elma-gohan.xyz/console/'
