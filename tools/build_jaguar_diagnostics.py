#!/usr/bin/env python3
"""Convert the audited extraction into the native diagnostic database (no PDF shipped).
Input: manual-data.js produced by import_jaguar_manual.py from the supplied source.
"""
import json,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
source=Path(sys.argv[1])
s=source.read_text();data=json.loads(s[len('window.JAGUAR_MANUAL='):-2])
def esc(s):return str(s).replace('\\','\\\\').replace('\t','\\t').replace('\n','\\n').replace('\r','')
rows=[]
for r in data['records']:
 fields=[v for f in r['fields'] for v in (f['label'],f['text'])]
 rows.append('\t'.join(map(esc,[r['code'],r['years'],r['section'],r['page'],*fields])))
out=ROOT/'library/src/main/java/com/fr3ts0n/ecu/prot/obd/res/jaguar'
(out/'diagnostic_records.tsv').write_text('\n'.join(rows)+'\n')
# Keep all source text as technical data, but UI separates non-table topics from DTCs.
table_pages={r['page'] for r in data['records']}
topics=[]
for p in data['pages']:
 lines=p['text'].splitlines()
 title=next((x.strip().rstrip(':') for x in lines if len(x.strip())>5 and not x.startswith('Jaguar S-TYPE') and x.isupper()),p['section']+' · '+p['years'])
 topics.append('\t'.join(map(esc,[p['page'],p['years'],p['section'],'table' if p['page'] in table_pages else 'topic',title,p['text']])))
(out/'diagnostic_topics.tsv').write_text('\n'.join(topics)+'\n')
print(len(rows),'diagnostic records;',len(topics),'technical text units;',len(topics)-len(table_pages),'non-table topics')
