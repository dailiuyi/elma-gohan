"""Map the existing service database environment without printing secrets."""
from pathlib import Path
import os
import shlex

source = Path('/etc/elma-gohan/elma-gohan.env')
values = {}
for line in source.read_text(encoding='utf-8').splitlines():
    line = line.strip()
    if not line or line.startswith('#') or '=' not in line:
        continue
    key, value = line.split('=', 1)
    parts = shlex.split(value, comments=True)
    values[key.removeprefix('export ').strip()] = parts[0] if parts else ''
required = ('DB_HOST', 'DB_PORT', 'DB_NAME', 'DB_USERNAME', 'DB_PASSWORD')
if any(key not in values for key in required):
    raise SystemExit('Required database environment names are missing')
settings = dict(zip(('PGHOST','PGPORT','PGDATABASE','PGUSER','PGPASSWORD'), (values[key] for key in required)))
settings.update(PGOPTIONS='-c default_transaction_read_only=on -c timezone=Asia/Shanghai -c statement_timeout=15000', PGCONNECT_TIMEOUT='10', PGAPPNAME='elma-console-readonly')
target = Path('/etc/elma-console/database.env')
target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
lines = []
for key, value in settings.items():
    if '\n' in value or '\r' in value or '\x00' in value:
        raise SystemExit('Invalid multiline environment value')
    escaped = value.replace('\\', '\\\\').replace('"', '\\"')
    lines.append(f'{key}="{escaped}"')
fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
with os.fdopen(fd, 'w', encoding='utf-8') as output:
    output.write('\n'.join(lines) + '\n')
os.chmod(target, 0o600)
print('DATABASE_ENVIRONMENT_READY')
