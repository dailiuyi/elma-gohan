"""Create and verify the first snapshot before publishing the new UI."""
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo
import json
import time
from urllib.request import Request, urlopen

base = 'http://127.0.0.1:8092/console/data/v1/'
def request(path, payload=None, token=None):
    headers = {'Host':'elma-gohan.xyz', 'Origin':'https://elma-gohan.xyz'}
    if token:
        headers['X-ELMA-CSRF'] = token
    data = None
    if payload is not None:
        headers['Content-Type'] = 'application/json'
        data = json.dumps(payload).encode()
    with urlopen(Request(base + path, data=data, headers=headers), timeout=10) as response:
        return json.load(response)

state = request('state')
end = datetime.now(ZoneInfo('Asia/Shanghai')).date()
request('refresh', {'from':str(end-timedelta(days=29)), 'to':str(end)}, state['csrfToken'])
for attempt in range(100):
    state = request('state')
    if not state['refresh']['running']:
        if state['refresh'].get('error'):
            raise SystemExit('Initial refresh failed; previous console is retained')
        snapshot = request('snapshot')
        assert snapshot['meta']['readOnlyVerified'] is True
        assert snapshot['meta']['periodStart'] == str(end-timedelta(days=29))
        assert snapshot['meta']['periodEnd'] == str(end)
        print('INITIAL_SNAPSHOT_READY', snapshot['id'], snapshot['meta']['snapshotAt'])
        break
    time.sleep(2)
else:
    raise SystemExit('Initial refresh exceeded its deployment deadline')
