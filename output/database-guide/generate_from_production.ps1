[CmdletBinding()]
param(
    [ValidateRange(1, 366)]
    [int]$Days = 30,

    [string]$SshHost = 'root@39.108.101.149',

    [string]$SshKey = (Join-Path $env:USERPROFILE '.ssh\elma_gohan_ed25519'),

    [string]$RemoteEnvironmentFile = '/etc/elma-gohan/elma-gohan.env',

    [string]$Output = 'dashboard.local.html'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($RemoteEnvironmentFile -notmatch '^/[A-Za-z0-9._/-]+$') {
    throw 'RemoteEnvironmentFile must be an absolute Linux path without spaces.'
}
if (-not (Test-Path -LiteralPath $SshKey -PathType Leaf)) {
    throw "SSH key not found: $SshKey"
}

$ssh = Get-Command ssh.exe -ErrorAction Stop
$python = Get-Command py.exe -ErrorAction Stop
$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$generator = Join-Path $scriptDirectory 'generate_dashboard.py'
$outputPath = if ([System.IO.Path]::IsPathRooted($Output)) {
    $Output
} else {
    Join-Path $scriptDirectory $Output
}
$knownHosts = Join-Path (Split-Path -Parent $SshKey) 'known_hosts'
$sshBaseArguments = @(
    '-i', $SshKey,
    '-o', 'IdentitiesOnly=yes',
    '-o', "UserKnownHostsFile=$knownHosts",
    '-o', 'StrictHostKeyChecking=accept-new',
    '-o', 'BatchMode=yes',
    '-o', 'ConnectTimeout=10'
)

function Invoke-RemoteShell {
    param([Parameter(Mandatory)][string]$Command)

    $commandBytes = [System.Text.Encoding]::UTF8.GetBytes($Command)
    $encodedCommand = [Convert]::ToBase64String($commandBytes)
    & $ssh.Source @sshBaseArguments $SshHost "echo '$encodedCommand' | base64 -d | sh"
}

$metadataCommand = "set -a; . '$RemoteEnvironmentFile'; " +
    'printf "%s\n%s\n%s\n%s\n" "$DB_HOST" "$DB_PORT" "$DB_NAME" "$DB_USERNAME"'
$metadata = @(Invoke-RemoteShell -Command $metadataCommand)
if ($LASTEXITCODE -ne 0 -or $metadata.Count -ne 4) {
    throw 'Could not read the production database endpoint from the remote service environment.'
}
$databaseHost, $databasePort, $databaseName, $databaseUser = $metadata
if (-not ($databasePort -as [int])) {
    throw 'The remote database port is invalid.'
}

$passwordCommand = "set -a; . '$RemoteEnvironmentFile'; " +
    'printf "%s" "$DB_PASSWORD"'
$databasePassword = [string](Invoke-RemoteShell -Command $passwordCommand)
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrEmpty($databasePassword)) {
    throw 'Could not read the production database credential from the remote service environment.'
}

$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
$listener.Start()
$localPort = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
$listener.Stop()

$tunnelArguments = @(
    $sshBaseArguments
    '-o', 'ExitOnForwardFailure=yes',
    '-o', 'ServerAliveInterval=15',
    '-N',
    '-L', "${localPort}:${databaseHost}:${databasePort}",
    $SshHost
)
$tunnel = $null
$pgNames = @('PGHOST', 'PGPORT', 'PGDATABASE', 'PGUSER', 'PGPASSWORD')
$previousEnvironment = @{}
foreach ($name in $pgNames) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

try {
    $tunnel = Start-Process -FilePath $ssh.Source -ArgumentList $tunnelArguments `
        -PassThru -WindowStyle Hidden
    $ready = $false
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        if ($tunnel.HasExited) {
            throw "SSH tunnel exited with code $($tunnel.ExitCode)."
        }
        $client = [System.Net.Sockets.TcpClient]::new()
        try {
            $connect = $client.ConnectAsync('127.0.0.1', $localPort)
            if ($connect.Wait(250) -and $client.Connected) {
                $ready = $true
                break
            }
        } catch {
            # The tunnel can need a brief moment before accepting connections.
        } finally {
            $client.Dispose()
        }
        Start-Sleep -Milliseconds 250
    }
    if (-not $ready) {
        throw 'SSH tunnel did not become ready.'
    }

    $env:PGHOST = '127.0.0.1'
    $env:PGPORT = [string]$localPort
    $env:PGDATABASE = $databaseName
    $env:PGUSER = $databaseUser
    $env:PGPASSWORD = $databasePassword

    Write-Host "Generating a $Days-day dashboard from $databaseName through a temporary SSH tunnel..."
    & $python.Source $generator --days $Days --output $outputPath
    if ($LASTEXITCODE -ne 0) {
        throw "Dashboard generation failed with code $LASTEXITCODE."
    }
    Write-Host "Dashboard refreshed: $outputPath"
} finally {
    $databasePassword = $null
    foreach ($name in $pgNames) {
        $previous = $previousEnvironment[$name]
        if ($null -eq $previous) {
            Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue
        } else {
            [Environment]::SetEnvironmentVariable($name, $previous, 'Process')
        }
    }
    if ($null -ne $tunnel -and -not $tunnel.HasExited) {
        Stop-Process -Id $tunnel.Id -Force
        Wait-Process -Id $tunnel.Id -ErrorAction SilentlyContinue
    }
}

exit 0
