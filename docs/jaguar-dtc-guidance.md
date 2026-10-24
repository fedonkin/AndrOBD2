# Complete Jaguar S-TYPE reference integration

## Source and exact coverage

The user supplied the complete **STYPE.pdf**, 309 pages, SHA-256:
`9300ef0dee9496439ab72af7a42f59170399f4a989209ff636b3fbf64b6447a6`.
The original Jaguar document (provided via JCNA) is retained byte-for-byte at
`androbd/src/main/assets/jaguar_manual/STYPE.pdf`. No original page, diagram,
footnote, warning or table is removed. Source attribution remains in the PDF.

| Section | Model years | PDF pages | Indexed DTC records |
|---|---|---|---|
| Powertrain | 2000 | 2–61 | 235 |
| Powertrain | 2001–2002 | 62–122 | 230 |
| Powertrain | 2003 | 123–196 | 255 |
| Body | 2000–2002 | 197–233 | 210 |
| Body | 2003 | 234–267 | 223 |
| Chassis | 2000–2002 | 268–282 | 70 |
| Chassis | 2003 | 283–299 | 97 |
| Network | 2003 | 300–309 | 55 |

Page 1 is the source introduction and applicability guide. Total: **1375 records,
799 distinct DTCs, 309 searchable pages**. Repeated codes retain their separate
module/year records, including repeated U2610 entries. Early/late-production
footnotes, including P0345/P1340 and P0346/P1341, remain intact.

## What is available in the application

Menu **Jaguar S-TYPE · полный справочник / full manual**:
- Search all DTC fields: system/module, fault description, monitoring conditions,
  Check Engine trip rules, other warnings, default actions, CM pins and possible
  causes, wherever the source provides those columns. Missing columns are not invented.
- Filter by model year (including ranges) and section. MY2002 searches also include
  the Body/Chassis tables labelled MY2000–2002.
- Search all page text, not only DTC tables: monitor drive cycles, KOEO/KOER
  descriptions, acronyms, definitions, diagnostic notes and source applicability.
- Original PDF bookmarks become the contents view.
- Each result links to its **original PDF page**, rendered offline with Android
  PdfRenderer; zoom and two-axis scrolling preserve wide tables without clipping.
- Search results are paginated, not truncated: all matches can be loaded.
- Search/filter state is retained across Activity recreation through local URLs.

With a Jaguar profile selected:
- Long press on a fault opens all source records matching that DTC.
- Existing fault cards and copied/scan reports now receive the full source-record
  reference, not only the earlier 17-code subset.
- The earlier reviewed EN/RU summaries remain for those 17 codes under X200/Auto,
  with their original MY2001–2002 applicability warning.

The reference viewer is manually available in any profile, but passive: it cannot
send ELM commands, change the vehicle profile, start Demo or alter recording.
Automatic fault guidance remains gated to the Jaguar profile.

## Applicability and language

These are **reference records, not an automatic determination of the car's ECU**.
All matching year/module variants are explicitly marked. Select the correct year,
VIN, engine and module before using a row. Powertrain MY2000 covers up to VIN L86901;
MY2001–2002 starts at L86902. The MY2003 sections do not establish compatibility
with MY2004–2007, even though the application also has a broad X202 profile.

All source text is preserved **in English**. Navigation, column captions and
scope warnings support Russian; the full 309-page document has **not** been
represented as a verified Russian translation. Existing reviewed Russian
summaries are retained, not substituted for the original source.

Possible causes are not a diagnosis. Descriptive default actions are not commands
to activate them. A JAG-only DTC in the document does not mean generic Mode 03 can
retrieve it. The reference may list diagnostic procedures/parameters without
specifying their wire commands, addresses or decoding formulas.

**This delivers the full document's information in the app. It does not add
unverified PDU/WDS self-test execution, programming, actuator controls or PID
formulas absent from the source.** Fuel-temperature EFT/EFT1 reference entries
are searchable, but a verified Jaguar temperature read command remains separate
work. No unsupported execution capability is claimed.

## Import and validation

`tools/import_jaguar_manual.py` uses PyMuPDF **2.0**, pinned to this source hash.
It preserves full page text and uses detected table cell rectangles to extract
text, rather than the table extractor's word-merging output. DTC column words are
independently checked against imported row codes, accounting for source asterisks.
Non-DTC explanatory tables remain searchable in full page text and original pages.
The audit is empty; mismatches make the importer exit unsuccessfully.
`docs/jaguar-manual-import.json` contains source hash, section ranges, bookmarks,
counts and audit results. Generated properties feed the Java report lookup;
generated JS data feeds the offline viewer. Neither needs a backend service.

Run:
```
python -m pip install pymupdf==2.0
python tools/import_jaguar_manual.py
python tools/check_jaguar_manual.py
# DOM tests (install jsdom in a temporary/external directory):
NODE_PATH=/path/to/node_modules node tools/test_jaguar_manual.cjs
```

Completed checks:
- Exact source hash and all **309/309 page text** comparisons.
- **1375 records / 799 codes**, all eight section counts and zero DTC-column audit discrepancies.
- Independent page inventory: all **262 DTC-table pages** accounted for (no missing or extra table pages).
- Java lookup for all **799** codes, repeated modules, production footnotes, profile isolation.
- Existing guidance/variant checks **56 passed**, Volvo checks **38 passed**.
- Non-GUI Java library ECJ compilation: zero errors.
- DOM tests: DTC search, range-aware year/module filters, all 309 pages, pagination,
  original links, contents and HTML injection escaping.
- Java syntax/XML/format checks.

Added Android instrumentation test `JaguarManualPdfTest` opens the actual shipped
PDF, asserts 309 pages and renders representative pages from every section.
**Android APK build, Gradle/JUnit runner and instrumentation/device tests were not
run here.** DOM tests are not a substitute for Android WebView/PdfRenderer tests.
No claim is made that every extracted cell has been manually proofread; the
original page is always available to resolve layout or transcription ambiguity.
