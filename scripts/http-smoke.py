#!/usr/bin/env python3
"""Exercise the running local demo. Creates at most one claim for seed account 1.

No credentials or redemption codes are written to the report. Uses Python stdlib.
The timing sample is a small local smoke load, not a capacity benchmark.
"""
import concurrent.futures
import datetime
import json
import math
import platform
from pathlib import Path
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
BASE = 'http://127.0.0.1:8080'
RESULTS = []

def request(path, method='GET', payload=None, token=None, content_type='application/json'):
    headers = {'Content-Type': content_type}
    if token: headers['Authorization'] = 'Bearer ' + token
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(BASE+path, data=data, headers=headers, method=method)
    begin = time.perf_counter()
    try:
        response = urllib.request.urlopen(req, timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read()
        body = json.loads(raw) if 'json' in response.headers.get('Content-Type', '') else raw.decode()
        return response.status, body, response.headers, (time.perf_counter()-begin)*1000

def check(name, condition):
    if not condition: raise AssertionError('Smoke check failed: ' + name)
    RESULTS.append({'check': name, 'passed': True})

def main():
    status, page, _, _ = request('/')
    check('HTML demonstration page', status == 200 and 'CampusLife' in page)
    status, spec, _, _ = request('/v3/api-docs')
    check('OpenAPI document', status == 200)
    protected = [('/api/auth/me', 'get'), ('/api/auth/logout', 'post'),
                 ('/api/shops/{id}', 'patch'), ('/api/vouchers/{id}/claims', 'post'),
                 ('/api/me/vouchers', 'get'), ('/api/merchant/shops', 'get'),
                 ('/api/merchant/shops/{id}', 'get'),
                 ('/api/merchant/vouchers/redemptions', 'post')]
    check('Swagger bearer requirements on all protected operations', all(
        {'bearerAuth': []} in spec['paths'][path][method].get('security', [])
        for path, method in protected))
    check('Unsupported HTTP method is 405', request('/api/shops', 'DELETE')[0] == 405)
    check('Unsupported body type is 415', request('/api/auth/code', 'POST', {}, content_type='text/plain')[0] == 415)
    status, shops, _, _ = request('/api/shops?page=1&size=10')
    check('Public seeded shop list', status == 200 and shops['data']['total'] == 3)
    check('Unauthenticated private query is 401', request('/api/me/vouchers')[0] == 401)
    status, issued, _, _ = request('/api/auth/code', 'POST', {'phone': '13800000001'})
    check('Seed user receives simulated development code', status == 200)
    check('Code request cooldown is 429', request('/api/auth/code', 'POST', {'phone':'13800000001'})[0] == 429)
    status, logged, headers, _ = request('/api/auth/login', 'POST',
                                       {'phone':'13800000001','code':issued['data']['code']})
    check('Seed user logs in and private response has no-store', status == 200 and headers.get('Cache-Control') == 'no-store')
    token = logged['data']['token']
    status, claimed, _, _ = request('/api/vouchers/1/claims', 'POST', token=token)
    check('First or previously persisted claim is recognized', status == 200 or
          (status == 409 and claimed['code'] == 'ALREADY_CLAIMED'))
    status, duplicate, _, _ = request('/api/vouchers/1/claims', 'POST', token=token)
    check('Repeated claim is rejected', status == 409 and duplicate['code'] == 'ALREADY_CLAIMED')
    status, mine, _, _ = request('/api/me/vouchers', token=token)
    check('Claim appears in private history', status == 200 and
          any(item['voucherId'] == 1 for item in mine['data']['items']))
    check('Logout succeeds', request('/api/auth/logout', 'POST', token=token)[0] == 200)
    check('Logged out token is invalid', request('/api/me/vouchers', token=token)[0] == 401)
    # Warm the cache explicitly. Sample includes client/network/request processing on loopback.
    request('/api/shops/1')
    start = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:
        responses = list(pool.map(lambda _: request('/api/shops/1'), range(200)))
    elapsed = time.perf_counter()-start
    check('200 warm public detail reads all return 200', all(item[0] == 200 for item in responses))
    timings = sorted(item[3] for item in responses)
    report = {
        'timestamp': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'base_url': BASE, 'platform': platform.system(), 'architecture': platform.machine(),
        'checks': RESULTS,
        'warm_read_sample': {'requests':200,'workers':16,'successes':200,
                            'elapsed_seconds':round(elapsed,4),'requests_per_second':round(200/elapsed,2),
                            'p50_ms':round(timings[99],2),'p95_ms':round(timings[math.ceil(200*0.95)-1],2),
                            'method':'Python urllib, loopback, Java debug session, warmed cache; one sample; not a capacity estimate'}
    }
    output = ROOT/'docs/evidence/http-smoke.json'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
    print(f'Passed {len(RESULTS)} checks; sanitized report: {output}')

if __name__ == '__main__': main()
