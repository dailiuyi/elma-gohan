"""Create an explicit, secret-free deployment bundle after build:console."""
from pathlib import Path
import argparse
import hashlib
import json
import tarfile

ROOT = Path(__file__).resolve().parents[3]
parser = argparse.ArgumentParser()
parser.add_argument('--output', required=True, type=Path)
args = parser.parse_args()
console = ROOT / 'output/admin-console'
dist = console / 'dist'
if not (dist / 'index.html').is_file():
    raise SystemExit('Run pnpm build:console before packaging')
files = []
for path in dist.rglob('*'):
    if path.is_file():
        files.append((path, Path('static') / path.relative_to(dist)))
for directory in (console / 'server', console / 'deploy'):
    for path in directory.iterdir():
        if path.is_file() and path.suffix in ('.py', '.sh', '.txt', '.service', '.conf'):
            files.append((path, path.relative_to(ROOT)))
legacy = ROOT / 'output/database-guide'
for path in legacy.glob('*.py'):
    files.append((path, path.relative_to(ROOT)))
for path in (legacy / 'data/china-prefecture-grid.json', legacy / 'THIRD_PARTY_NOTICES.md'):
    files.append((path, path.relative_to(ROOT)))
args.output.parent.mkdir(parents=True, exist_ok=True)
with tarfile.open(args.output, 'w:gz') as archive:
    for source, target in sorted(files, key=lambda pair: str(pair[1])):
        archive.add(source, arcname=str(target).replace('\\','/'), recursive=False)
print(json.dumps({'archive':str(args.output.resolve()), 'files':len(files), 'sha256':hashlib.sha256(args.output.read_bytes()).hexdigest()}))
