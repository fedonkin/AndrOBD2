#!/usr/bin/env python3
"""Reproducible, offline import of the user-supplied Jaguar PDF (PyMuPDF 2.0).
Keeps original pages, all searchable page text, bookmarks, and table cell text.
Never invents ECU commands or merges rows sharing a DTC across modules/years.
"""
import hashlib, json, re, sys
from pathlib import Path
import pymupdf
ROOT=Path(__file__).resolve().parents[1]
PDF=Path(sys.argv[1])
ASSET=Path(sys.argv[2])
ASSET.mkdir(parents=True,exist_ok=True)
SECTIONS=[(2,61,'Powertrain','2000'),(62,122,'Powertrain','2001–2002'),(123,196,'Powertrain','2003'),(197,233,'Body','2000–2002'),(234,267,'Body','2003'),(268,282,'Chassis','2000–2002'),(283,299,'Chassis','2003'),(300,309,'Network','2003')]
CODE=re.compile(r'\b[PBCU][0-3][0-9A-F]{3}\b')
def clean(s): return '\n'.join(x.rstrip() for x in s.strip().splitlines())
def prop(s):return s.replace('\\','\\\\').replace('\n','\\n').replace('\r','').replace('=','\\=').replace(':','\\:')
def main():
 assert hashlib.sha256(PDF.read_bytes()).hexdigest() == '9300ef0dee9496439ab72af7a42f59170399f4a989209ff636b3fbf64b6447a6', 'Different PDF: review page/section boundaries before importing'
 doc=pymupdf.open(PDF)
 assert len(doc)==309
 pages=[];records=[];audit=[];bundle={}
 for i,page in enumerate(doc):
  num=i+1
  section=next((s for s in SECTIONS if s[0]<=num<=s[1]),(1,1,'Introduction','All'))
  text=clean(page.get_text())
  assert text, f'No searchable text on page {num}'
  pages.append(dict(page=num,section=section[2],years=section[3],text=text))
  table_codes=[]; anchors=[]
  for table in page.find_tables().tables:
   rows=table.rows
   if not rows:continue
   headers=[clean(page.get_text(clip=pymupdf.Rect(c))) if c else '' for c in rows[0].cells]
   if len(headers)<6 or headers[0]!='DTC':continue
   left=rows[0].cells[0][0]; right=rows[0].cells[0][2]
   anchors.extend(w[4].rstrip('*') for w in page.get_text('words') if left <= (w[0]+w[2])/2 <= right and table.bbox[1]<=w[1]<=table.bbox[3] and CODE.fullmatch(w[4].rstrip('*')))
   for row in rows[1:]:
    cells=[clean(page.get_text(clip=pymupdf.Rect(c))) if c else '' for c in row.cells]
    codes=CODE.findall(cells[0])
    if not codes:
     if any(cells): audit.append(dict(page=num,type='non-code table row',cells=cells))
     continue
    fields=[dict(label=headers[k] or f'Column {k+1}',text=cell) for k,cell in enumerate(cells) if k>0]
    for code in codes:
     table_codes.append(code)
     record=dict(code=code,sourceCode=cells[0],page=num,section=section[2],years=section[3],fields=fields)
     records.append(record)
     body=f"{code} | {section[2]} | MY {section[3]} | PDF p. {num}\n"+'\n'.join(f["label"]+': '+f["text"] for f in fields)
     bundle.setdefault(code,[]).append(body)
  # Cross-check independent first-column text anchors on DTC table pages.
  # anchors are independently extracted words in each actual DTC table column.
  if sorted(anchors)!=sorted(table_codes):audit.append(dict(page=num,type='column anchor mismatch',anchors=anchors,records=table_codes))
 metadata=dict(title='Jaguar S-TYPE DTC Summaries',pages=len(doc),sha256=hashlib.sha256(PDF.read_bytes()).hexdigest(),records=len(records),uniqueCodes=len(bundle),sections=SECTIONS,bookmarks=doc.get_toc(),audit=audit,extractor=pymupdf.VersionBind)
 data=dict(metadata=metadata,pages=pages,records=records)
 (ASSET/'manual-data.js').write_text('window.JAGUAR_MANUAL='+json.dumps(data,ensure_ascii=False,separators=(',',':'))+';\n')
 (ROOT/'docs/jaguar-manual-import.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2)+'\n')
 print(json.dumps(metadata,ensure_ascii=False,indent=2))
 if audit:raise SystemExit('REVIEW REQUIRED: inspect import audit; structured coverage is not complete.')
if __name__=='__main__':main()
