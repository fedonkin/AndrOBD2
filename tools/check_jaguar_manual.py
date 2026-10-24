#!/usr/bin/env python3
"""Fast independent coverage checks; needs pymupdf, not the Android SDK."""
import collections, hashlib, json, re
from pathlib import Path
import pymupdf
root=Path(__file__).resolve().parents[1]
asset=root/'androbd/src/main/assets/jaguar_manual'
raw=(asset/'manual-data.js').read_text()
data=json.loads(raw[len('window.JAGUAR_MANUAL='):-2])
pdf=pymupdf.open(asset/'STYPE.pdf')
assert hashlib.sha256((asset/'STYPE.pdf').read_bytes()).hexdigest()==data['metadata']['sha256']
assert len(pdf)==len(data['pages'])==309
assert not data['metadata']['audit']
for i,page in enumerate(pdf):
    assert data['pages'][i]['page']==i+1
    assert data['pages'][i]['text']=='\n'.join(x.rstrip() for x in page.get_text().strip().splitlines())
assert len(data['records'])==1375
assert len({r['code'] for r in data['records']})==799
for row in data['records']:
    assert row['code'] in data['pages'][row['page']-1]['text']
    fields={f['label']:f['text'] for f in row['fields']}
    assert fields['FAULT DESCRIPTION'] and 'POSSIBLE CAUSES' in fields
# Independent page-level inventory: catches an entirely missed DTC table.
candidates=set()
for i,page in enumerate(pdf):
    text=page.get_text()
    if 'FAULT DESCRIPTION' in text and 'POSSIBLE CAUSES' in text and any(
            w[0]<50 and re.fullmatch(r'[PBCU][0-3][0-9A-F]{3}\*?',w[4])
            for w in page.get_text('words')):
        candidates.add(i+1)
assert candidates=={r['page'] for r in data['records']} and len(candidates)==262
expected={('Powertrain','2000'):235,('Powertrain','2001–2002'):230,('Powertrain','2003'):255,
          ('Body','2000–2002'):210,('Body','2003'):223,('Chassis','2000–2002'):70,
          ('Chassis','2003'):97,('Network','2003'):55}
assert collections.Counter((r['section'],r['years']) for r in data['records'])==expected
print('PASS: source hash; all 309 page texts; 1375 rows / 799 codes; all eight sections; zero import audit discrepancies')
