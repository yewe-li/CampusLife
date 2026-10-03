#!/usr/bin/env python3
"""Compare one demo claim before/after an externally performed application restart.

Optional helper for the original Mac user-level service layout, not a generic installer.
Only reads the known local development database. Does not save tokens/redemption codes.
"""
import datetime
import importlib.util
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
if len(sys.argv) != 2 or sys.argv[1] not in ('before', 'after'):
    raise SystemExit('Usage: persistence-check.py before|after (original Mac service layout only)')
snapshot = ROOT/'work/logs/campuslife/persistence-before.json'
mysql = Path.home()/'.local/bin/mysql'
config = Path.home()/'.config/campuslife-dev/mysql-admin.cnf'
if not mysql.is_file() or not config.is_file():
    raise SystemExit('This optional helper requires the original Mac service layout; see docs/setup.md section 7.')
if sys.argv[1] == 'after' and not snapshot.is_file():
    raise SystemExit('Missing snapshot: run persistence-check.py before, then restart the application first.')
query = "SELECT id FROM campuslife.voucher_orders WHERE user_id=1 AND voucher_id=1; SELECT stock FROM campuslife.voucher_stock WHERE voucher_id=1;"
command = [str(mysql), '--defaults-file='+str(config),
           '--batch','--skip-column-names']
rows = subprocess.run(command,input=query,text=True,capture_output=True,check=True).stdout.splitlines()
if len(rows) != 2: raise SystemExit('Run http-smoke.py to create the demonstration claim first')
if sys.argv[1] == 'before':
    snapshot.parent.mkdir(parents=True, exist_ok=True)
    snapshot.write_text(json.dumps({'order_id':rows[0],'stock':int(rows[1])}))
    print('Saved one development claim snapshot; no secrets or redemption code included.')
elif sys.argv[1] == 'after':
    before = json.loads(snapshot.read_text())
    assert rows[0] == before['order_id'] and int(rows[1]) == before['stock']
    spec = importlib.util.spec_from_file_location('smoke', ROOT/'scripts/http-smoke.py')
    smoke = importlib.util.module_from_spec(spec); spec.loader.exec_module(smoke)
    status, issued, _, _ = smoke.request('/api/auth/code','POST',{'phone':'13800000001'})
    assert status == 200
    status, logged, _, _ = smoke.request('/api/auth/login','POST',{'phone':'13800000001','code':issued['data']['code']})
    assert status == 200
    token = logged['data']['token']
    try:
        status, mine, _, _ = smoke.request('/api/me/vouchers', token=token)
        assert status == 200 and any(item['id'] == rows[0] for item in mine['data']['items'])
    finally:
        smoke.request('/api/auth/logout','POST',token=token)
    result = {'timestamp':datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'action':'VS Code Java debug restart, then database and authenticated HTTP reads',
              'same_order_after_restart':True,'same_stock_after_restart':True,
              'authenticated_history_after_restart':True}
    output = ROOT/'docs/evidence/persistence.json'
    output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    snapshot.unlink()
    print('Database and authenticated HTTP history preserved after restart; report: '+str(output))
