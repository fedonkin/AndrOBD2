#!/usr/bin/env python3
"""Native database integrity. Optional argument: audited intermediate manual-data.js."""
import collections,json,sys
from pathlib import Path
root=Path(__file__).resolve().parents[1]
data=root/'library/src/main/java/com/fr3ts0n/ecu/prot/obd/res/jaguar'
def unesc(s):
 out=[];i=0
 while i<len(s):
  c=s[i]
  if c=='\\' and i+1<len(s):
   i+=1;c={'n':'\n','t':'\t','\\':'\\'}.get(s[i],s[i])
  out.append(c);i+=1
 return ''.join(out)
rows=[[unesc(c) for c in line.split('\t')] for line in (data/'diagnostic_records.tsv').read_text().splitlines()]
topics=[[unesc(c) for c in line.split('\t')] for line in (data/'diagnostic_topics.tsv').read_text().splitlines()]
assert len(rows)==1375 and len({r[0] for r in rows})==799
assert len(topics)==309 and sum(t[3]=='topic' for t in topics)==47
for r in rows:
 fields=dict(zip(r[4::2],r[5::2]));assert fields['FAULT DESCRIPTION'] and 'POSSIBLE CAUSES' in fields
expected={('Powertrain','2000'):235,('Powertrain','2001–2002'):230,('Powertrain','2003'):255,('Body','2000–2002'):210,('Body','2003'):223,('Chassis','2000–2002'):70,('Chassis','2003'):97,('Network','2003'):55}
assert collections.Counter((r[2],r[1]) for r in rows)==expected
if len(sys.argv)>1:
 s=Path(sys.argv[1]).read_text();original=json.loads(s[len('window.JAGUAR_MANUAL='):-2])
 assert original['metadata']['audit']==[]
 for row,source in zip(rows,original['records']):
  assert row==[source['code'],source['years'],source['section'],str(source['page']),*[v for f in source['fields'] for v in (f['label'],f['text'])]]
 for topic,page in zip(topics,original['pages']):assert topic[5]==page['text']
assert not list((root/'androbd/src/main').rglob('*.pdf'))
assert not (root/'androbd/src/main/assets/jaguar_manual').exists()
print('PASS: 1375 typed native records, 799 codes, 309 technical units, all fields preserved, no PDF/viewer assets shipped')
