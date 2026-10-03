#!/usr/bin/env python3
"""Generate a sanitized, reproducible evidence summary from the last Maven verify."""
import datetime
import argparse
import hashlib
import json
import platform
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--command', default='sh scripts/dev.sh test',
                    help='Actual successful full verification command used for these reports')
args = parser.parse_args()
def suites(folder):
    records = []
    for file in sorted((ROOT/'target'/folder).glob('TEST-*.xml')):
        suite = ET.parse(file).getroot()
        records.append({'name':suite.get('name'), **{k:int(suite.get(k,0)) for k in
                        ('tests','failures','errors','skipped')},
                        'seconds':float(suite.get('time',0)),
                        'cases':[case.get('name') for case in suite.findall('testcase')]})
    if not records: raise SystemExit('Missing reports: run scripts/dev.sh test first')
    return records

reports = {'unit':suites('surefire-reports'),'integration':suites('failsafe-reports')}
if any(s[k] for group in reports.values() for s in group for k in ('failures','errors','skipped')):
    raise SystemExit('Tests failed or were skipped; refusing to publish a successful verification report')
coverage = {counter.get('type'):{'covered':int(counter.get('covered')),
                               'missed':int(counter.get('missed'))}
            for counter in ET.parse(ROOT/'target/site/jacoco/jacoco.xml').getroot().findall('counter')}
files = [ROOT/'pom.xml'] + sorted(p for p in (ROOT/'src').rglob('*') if p.is_file())
fingerprint = hashlib.sha256()
for file in files:
    fingerprint.update(str(file.relative_to(ROOT)).encode()+b'\0'+file.read_bytes()+b'\0')
report = {'generated_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),
          'platform':platform.system(), 'architecture':platform.machine(),
          'command':args.command, 'source_sha256':fingerprint.hexdigest(),
          'suite_counts':{kind:sum(s['tests'] for s in group) for kind,group in reports.items()},
          'suites':reports,'coverage':coverage,
          'scope':'Local MySQL 8.4.11 / Redis 8.10.2; no H2 substitutes; GitHub CI not executed'}
output = ROOT/'docs/evidence/maven-verify.json'
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'suite_counts':report['suite_counts'],'coverage':coverage,'report':str(output)},indent=2))
