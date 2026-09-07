"""Verify public authenticated assets, live refresh, and snapshot consistency."""
from pathlib import Path
import argparse
import base64
from datetime import datetime, timedelta
import hashlib
import json
import re
import subprocess
import time
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parents[3]
parser = argparse.ArgumentParser()
parser.add_argument('--refresh', action='store_true', help='Verify 7-day and 30-day read-only refreshes')
args = parser.parse_args()
credentials = dict(line.split('=', 1) for line in (ROOT / 'output/merged-console/.deploy-credentials').read_text(encoding='utf-8-sig').splitlines() if '=' in line)
authorization = base64.b64encode((credentials['username'] + ':' + credentials['password']).encode()).decode()
BASE = 'https://elma-gohan.xyz'

def request(path, *, auth=True, payload=None, csrf=None, origin=None):
    lines = []
    if auth:
        lines.append('header = "Authorization: Basic ' + authorization + '"')
    if origin:
        lines.append('header = ' + json.dumps('Origin: ' + origin))
    if csrf:
        lines.append('header = ' + json.dumps('X-ELMA-CSRF: ' + csrf))
    if payload is not None:
        lines.extend(['header = "Content-Type: application/json"', 'request = "POST"', 'data = ' + json.dumps(json.dumps(payload))])
    result = subprocess.run(['curl.exe', '--silent', '--show-error', '--max-time', '25', '--config', '-', '--write-out', '\n%{http_code}', BASE + path], input=('\n'.join(lines)+'\n').encode(), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        raise RuntimeError('HTTPS transport failed, curl exit ' + str(result.returncode))
    body, status = result.stdout.rsplit(b'\n', 1)
    return int(status), body

def get_json(path):
    status, body = request(path)
    assert status == 200, f'Unexpected HTTP status {status} for {path}'
    return json.loads(body)

status, page = request('/console/')
assert status == 200
expected = ROOT / 'output/admin-console/dist/index.html'
assert hashlib.sha256(page).digest() == hashlib.sha256(expected.read_bytes()).digest(), 'HTML differs from the built release'
assets = re.findall(r'(?:src|href)="(/console/assets/[^"]+)"', page.decode())
assert assets, 'Missing static assets'
for asset in assets:
    status, body = request(asset)
    assert status == 200
    local = ROOT / 'output/admin-console/dist' / asset.removeprefix('/console/')
    assert hashlib.sha256(body).digest() == hashlib.sha256(local.read_bytes()).digest(), 'Asset differs from release'
for path in ('/console/', '/console/data/v1/state', '/console/data/v1/snapshot', '/console/legacy/', '/console/app/'):
    assert request(path, auth=False)[0] == 401, 'Authentication boundary changed'
assert request('/console/legacy/')[0] == 200
assert request('/console/app/')[0] == 200
state = get_json('/console/data/v1/state')
initial_id = state['currentId']
snapshot = get_json('/console/data/v1/snapshot')
assert snapshot['meta']['readOnlyVerified'] is True
assert snapshot['meta']['sourceMode'] == 'database'
end = datetime.now(ZoneInfo('Asia/Shanghai')).date()
valid = {'from': str(end-timedelta(days=6)), 'to': str(end)}
assert request('/console/data/v1/refresh', payload=valid, origin=BASE, csrf='invalid')[0] == 403
assert request('/console/data/v1/refresh', payload=valid, origin='https://example.invalid', csrf=state['csrfToken'])[0] == 403
assert request('/console/data/v1/refresh', payload={'from':str(end), 'to':str(end-timedelta(days=1))}, origin=BASE, csrf=state['csrfToken'])[0] == 400
if args.refresh:
    for days in (7, 30):
        state = get_json('/console/data/v1/state')
        assert not state['refresh']['running'], 'Another authorized refresh is running; retry later'
        bounds = {'from':str(end-timedelta(days=days-1)), 'to':str(end)}
        status, body = request('/console/data/v1/refresh', payload=bounds, origin=BASE, csrf=state['csrfToken'])
        assert status == 202, 'Manual refresh did not start'
        for _ in range(95):
            state = get_json('/console/data/v1/state')
            if not state['refresh']['running']:
                assert not state['refresh']['error'], 'Refresh failed; previous snapshot is preserved'
                break
            time.sleep(2)
        else:
            raise RuntimeError('Manual refresh did not finish')
        snapshot = get_json('/console/data/v1/snapshot')
        assert snapshot['meta']['periodStart'] == bounds['from'] and snapshot['meta']['periodEnd'] == bounds['to']
        assert len(snapshot['daily']) == days
        assert snapshot['meta']['readOnlyVerified'] is True
        o, loc = snapshot['overview'], snapshot['locations']
        assert sum(p['requests'] for p in loc['points']) + loc['unmappedRequests'] == o['periodRecommendations']
        assert sum(p['anonymousIds'] for p in loc['points']) + loc['unmappedAnonymousIds'] == o['periodActiveIds']
        assert sum(row['users'] for row in snapshot['analytics']['frequency']) == o['periodActiveIds']
        print('PUBLIC_REFRESH_VERIFIED', days, 'days', snapshot['meta']['queryDurationMs'], 'ms')
    archived = get_json('/console/data/v1/snapshot?id=' + initial_id)
    assert archived['id'] == initial_id
print(json.dumps({'result':'PUBLIC_CONSOLE_VERIFIED','assets':len(assets),'snapshotAt':snapshot['meta']['snapshotAt'], 'periodStart':snapshot['meta']['periodStart'],'periodEnd':snapshot['meta']['periodEnd'],'history':len(state['history'])}))
