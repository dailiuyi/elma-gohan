[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$migrationDir = Join-Path $repoRoot 'backend\src\main\resources\db\migration'
$generator = Join-Path $repoRoot 'output\database-guide\generate_dashboard.py'
$template = Join-Path $repoRoot 'output\database-guide\index.html'
$pgBin = Split-Path -Parent (Get-Command psql.exe -ErrorAction Stop).Source
$tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$tempRoot = [IO.Path]::GetFullPath((Join-Path $tempBase ("elma-dashboard-pg-" + [guid]::NewGuid().ToString('N'))))
$dataDir = Join-Path $tempRoot 'data'
$logFile = Join-Path $tempRoot 'postgres.stdout.log'
$errorLogFile = Join-Path $tempRoot 'postgres.stderr.log'
$outputFile = Join-Path $tempRoot 'dashboard.html'
$databaseName = 'elma_dashboard_test'
$started = $false

$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$listener.Start()
$port = ([Net.IPEndPoint]$listener.LocalEndpoint).Port
$listener.Stop()

$previousEnvironment = @{}
foreach ($name in 'PGHOST', 'PGPORT', 'PGDATABASE', 'PGUSER', 'PGPASSWORD') {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

try {
    New-Item -ItemType Directory -Path $tempRoot | Out-Null
    & (Join-Path $pgBin 'initdb.exe') -D $dataDir -A trust -U postgres --encoding=UTF8 --no-locale | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "initdb failed with exit code $LASTEXITCODE" }

    $postgresArguments = @('-D', $dataDir, '-p', [string]$port, '-h', '127.0.0.1', '-F')
    $postgresProcess = Start-Process -FilePath (Join-Path $pgBin 'postgres.exe') -ArgumentList $postgresArguments -PassThru -WindowStyle Hidden -RedirectStandardOutput $logFile -RedirectStandardError $errorLogFile
    $started = $true

    $ready = $false
    for ($attempt = 0; $attempt -lt 100; $attempt++) {
        & (Join-Path $pgBin 'pg_isready.exe') -q -h 127.0.0.1 -p $port -U postgres
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        if ($postgresProcess.HasExited) { break }
        Start-Sleep -Milliseconds 100
    }
    if (-not $ready) {
        $details = if (Test-Path -LiteralPath $errorLogFile) { (Get-Content -LiteralPath $errorLogFile -Tail 8) -join ' | ' } else { 'no error log' }
        throw "temporary PostgreSQL did not become ready: $details"
    }

    & (Join-Path $pgBin 'createdb.exe') -h 127.0.0.1 -p $port -U postgres $databaseName
    if ($LASTEXITCODE -ne 0) { throw "createdb failed with exit code $LASTEXITCODE" }

    $migrations = Get-ChildItem -LiteralPath $migrationDir -Filter 'V*.sql' | Sort-Object {
        if ($_.Name -match '^V(\d+)__') { [int]$Matches[1] } else { [int]::MaxValue }
    }
    foreach ($migration in $migrations) {
        & (Join-Path $pgBin 'psql.exe') -X -v ON_ERROR_STOP=1 -h 127.0.0.1 -p $port -U postgres -d $databaseName -f $migration.FullName | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "migration failed: $($migration.Name)" }
    }

    $env:PGHOST = '127.0.0.1'
    $env:PGPORT = [string]$port
    $env:PGDATABASE = $databaseName
    $env:PGUSER = 'postgres'
    Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue

    & py $generator --template $template --output $outputFile --days 7 --max-map-points 20
    if ($LASTEXITCODE -ne 0) { throw "dashboard generator failed with exit code $LASTEXITCODE" }

    $html = Get-Content -LiteralPath $outputFile -Raw -Encoding utf8
    if (-not $html.Contains('"readOnlyVerified":true')) { throw 'generated snapshot did not confirm read-only mode' }
    if (-not $html.Contains('"sourceMode":"database"')) { throw 'generated snapshot is not marked as database mode' }
    if (-not $html.Contains('id="schema-view"')) { throw 'database guide was not preserved' }
    if ($html -match '<script\b[^>]*\bsrc\s*=') { throw 'generated HTML contains an external script' }

    Write-Output "POSTGRES_INTEGRATION_OK version=17 migrations=$($migrations.Count) read_only=true"
}
finally {
    foreach ($name in $previousEnvironment.Keys) {
        $value = $previousEnvironment[$name]
        if ($null -eq $value) {
            [Environment]::SetEnvironmentVariable($name, $null, 'Process')
        }
        else {
            [Environment]::SetEnvironmentVariable($name, $value, 'Process')
        }
    }
    if ($started) {
        & (Join-Path $pgBin 'pg_ctl.exe') -D $dataDir -m fast -W stop | Out-Null
        for ($attempt = 0; $attempt -lt 100 -and (Test-Path -LiteralPath (Join-Path $dataDir 'postmaster.pid')); $attempt++) {
            Start-Sleep -Milliseconds 100
        }
        if (Test-Path -LiteralPath (Join-Path $dataDir 'postmaster.pid')) {
            throw "temporary PostgreSQL did not stop: $dataDir"
        }
    }
    if (Test-Path -LiteralPath $tempRoot) {
        $resolvedTarget = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $tempRoot).Path)
        if (-not $resolvedTarget.StartsWith($tempBase, [StringComparison]::OrdinalIgnoreCase)) {
            throw "refusing to remove temp path outside the temp directory: $resolvedTarget"
        }
        Remove-Item -LiteralPath $resolvedTarget -Recurse -Force
    }
}
