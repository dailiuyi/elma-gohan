[CmdletBinding()]
param(
    [string]$SshHost = 'elma-gohan',

    [string]$SshKey = '',

    [switch]$FromPublic
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$ssh = Get-Command ssh.exe -ErrorAction Stop
$scp = Get-Command scp.exe -ErrorAction Stop
$python = Get-Command python.exe -ErrorAction SilentlyContinue
if ($null -eq $python) {
    $python = Get-Command py.exe -ErrorAction Stop
}

$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent (Split-Path -Parent $scriptDirectory)
$assemble = Join-Path $scriptDirectory 'assemble.py'
$html = Join-Path $scriptDirectory 'index.html'
$remoteInstall = Join-Path $scriptDirectory 'install-remote.sh'
$snippet = Join-Path $repoRoot 'deploy\nginx\elma-console.conf'
$credentials = Join-Path $scriptDirectory '.deploy-credentials'

if (-not (Test-Path -LiteralPath $assemble)) { throw "Missing $assemble" }
if (-not (Test-Path -LiteralPath $remoteInstall)) { throw "Missing $remoteInstall" }
if (-not (Test-Path -LiteralPath $snippet)) { throw "Missing $snippet" }

$sshBaseArguments = @(
    '-o', 'IdentitiesOnly=yes',
    '-o', 'StrictHostKeyChecking=accept-new',
    '-o', 'BatchMode=yes',
    '-o', 'ConnectTimeout=15'
)
if (-not [string]::IsNullOrWhiteSpace($SshKey)) {
    if (-not (Test-Path -LiteralPath $SshKey -PathType Leaf)) {
        throw "SSH key not found: $SshKey"
    }
    $sshBaseArguments = @('-i', $SshKey) + $sshBaseArguments
}

$assembleArguments = @($assemble)
if ($FromPublic) {
    $assembleArguments += '--from-public'
}
& $python.Source @assembleArguments
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $html)) {
    throw 'Failed to assemble the merged console HTML.'
}

& $scp.Source @sshBaseArguments $html "${SshHost}:/tmp/elma-console-index.html"
if ($LASTEXITCODE -ne 0) { throw 'Failed to upload index.html.' }
& $scp.Source @sshBaseArguments $snippet "${SshHost}:/tmp/elma-console.snippet.conf"
if ($LASTEXITCODE -ne 0) { throw 'Failed to upload nginx snippet.' }
& $scp.Source @sshBaseArguments $remoteInstall "${SshHost}:/tmp/elma-console-install.sh"
if ($LASTEXITCODE -ne 0) { throw 'Failed to upload install script.' }

& $ssh.Source @sshBaseArguments $SshHost 'sed -i "s/\r$//" /tmp/elma-console-install.sh /tmp/elma-console.snippet.conf'
if ($LASTEXITCODE -ne 0) { throw 'Failed to normalize uploaded script line endings.' }

$remoteOutput = & $ssh.Source @sshBaseArguments $SshHost 'sh /tmp/elma-console-install.sh'
if ($LASTEXITCODE -ne 0) {
    throw "Remote install failed:`n$remoteOutput"
}
Write-Output $remoteOutput

$passwordLine = @($remoteOutput) | Where-Object { $_ -like 'CONSOLE_PASSWORD_CREATED *' }
if ($passwordLine) {
    $password = ($passwordLine -split ' ', 2)[1]
    @(
        'url=https://elma-gohan.xyz/console/'
        'username=elma'
        "password=$password"
    ) | Set-Content -LiteralPath $credentials -Encoding utf8
    Write-Output "CONSOLE_CREDENTIALS $credentials"
}

$verifyUser = 'elma'
$verifyPassword = $null
if (Test-Path -LiteralPath $credentials) {
    foreach ($line in Get-Content -LiteralPath $credentials) {
        if ($line -like 'password=*') {
            $verifyPassword = $line.Substring('password='.Length)
        }
    }
}

$unauth = & $ssh.Source @sshBaseArguments $SshHost 'curl -sI --resolve elma-gohan.xyz:443:127.0.0.1 https://elma-gohan.xyz/console/'
if ($unauth -notmatch '401') {
    throw "Expected 401 without credentials. Response:`n$unauth"
}

if ($verifyPassword) {
    $authHeader = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${verifyUser}:${verifyPassword}"))
    $authBody = & $ssh.Source @sshBaseArguments $SshHost "curl -fsS --resolve elma-gohan.xyz:443:127.0.0.1 -H `"Authorization: Basic $authHeader`" https://elma-gohan.xyz/console/"
    if ($authBody -notmatch 'id="elma-product"' -or $authBody -notmatch 'id="elma-ops"' -or $authBody -notmatch 'elma-dashboard-data') {
        throw 'Authenticated page is missing the merged product or ops content.'
    }
}

$blog = & $ssh.Source @sshBaseArguments $SshHost 'curl -sI --resolve elma-gohan.xyz:443:127.0.0.1 https://elma-gohan.xyz/'
$api = & $ssh.Source @sshBaseArguments $SshHost 'curl -sI --resolve api.elma-gohan.xyz:443:127.0.0.1 https://api.elma-gohan.xyz/health'
if ($blog -notmatch '200' -and $blog -notmatch '301' -and $blog -notmatch '302') {
    throw "Blog homepage check failed:`n$blog"
}
if ($api -notmatch '200') {
    throw "API health check failed:`n$api"
}

Write-Output 'DEPLOY_OK https://elma-gohan.xyz/console/'
