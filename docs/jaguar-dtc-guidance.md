# Jaguar native diagnostic data — no document viewer

The document viewer added in e97f675 was the wrong implementation for the user's
request and has been removed: no PDF, HTML/JS assets, WebView, page renderer or
page-opening workflow is shipped. The source information is now application data.

## Native database

`JaguarDiagnostics` reads two UTF-8 structured resources in the Jaguar brand package:
- `diagnostic_records.tsv`: **1375 typed records / 799 distinct DTCs**. Fields are
  separate: code, model-year applicability, system/module, description, possible
  causes, monitoring conditions, Check Engine trip rules, warnings, default action,
  connector pins. Only fields present in the source are populated.
- `diagnostic_topics.tsv`: all **309 source text units** retained as technical
  data, including **47 non-table procedure/definition topics**. The UI presents
  these separately from fault records; table text duplicates are retained for
  integrity but are not displayed as another page browser.

No same-code rows are merged across years/modules. All original field values and
technical text were compared against the previously audited extraction: exact
matches, including production-change footnotes. Scope remains the supplied
STYPE source, MY2000–2003; no data for later cars is invented.

## Used by the program

- Native **«Диагностика Jaguar»** screen: model year, system filter, DTC/symptom
  search, structured fault cards, procedures/technical data and copying results.
- A long press on a Jaguar fault opens native diagnostics filtered by its code.
- **The normal fault list, copied DTC report and all-module scan report now use
  the applicable database description**, instead of relying solely on the old
  generic description. Causes/conditions/default actions are also included.
- `jaguar_diagnostic_year` is persisted; it can be selected in the diagnostic
  screen or Settings. Year changes refresh current DTC rows.
- An unselected year prompts selection, instead of displaying every year's rows.
- MY2002 includes the appropriate powertrain records and shared MY2000–2002
  body/chassis records. MY2004+ does not silently inherit MY2003 data.
- Existing reviewed Russian summaries are retained **only for MY2001–2002**.
- Automatic data lookup remains Jaguar-profile-only. Standard/Volvo/KIA do not
  receive Jaguar descriptions or cause lists. The screen itself is passive and
  never starts Demo, polling, coding, programming or actuator commands.

The UI uses Android widgets and scrollable native field cards, not document pages.
Filters are a ListView header so small/landscape screens can scroll the controls
rather than losing the results area.

## Limits that must remain explicit

Model year is chosen explicitly, not inferred from a registration date. The
powertrain applicability warning states VIN through L86901 for MY2000 and from
L86902 for MY2001–2002. Engine and module qualifiers remain visible. When a DTC
has multiple applicable modules, they remain separate alternatives; the program
does not invent which ECU reported it.

Possible causes are checks to consider, not a part-replacement diagnosis. The
code must actually be reported before automatic DTC guidance is used; no DTC is
invented from live values. Information about KOEO/KOER, default actions and
actuator tests is technical data, not fabricated executable ELM commands.

Source field text is English; native controls/field names and existing reviewed
summaries support Russian. A complete verified Russian translation and missing
wire protocols are not claimed by this change.

## Provenance / regeneration

Source: user-supplied STYPE.pdf, SHA-256
`9300ef0dee9496439ab72af7a42f59170399f4a989209ff636b3fbf64b6447a6`.
The source is **not bundled in the application**.
`docs/jaguar-manual-import.json` records the earlier zero-discrepancy extraction
and eight-section inventory. All 262 DTC-table pages were accounted for then.

Developer conversion (outside the APK):
```
python -m pip install pymupdf==2.0
python tools/import_jaguar_manual.py /path/to/STYPE.pdf /tmp/jaguar-import
python tools/build_jaguar_diagnostics.py /tmp/jaguar-import/manual-data.js
python tools/check_jaguar_manual.py /tmp/jaguar-import/manual-data.js
```

## Validation

- Native records, all original fields and all 309 text units compared with the
  audited extraction: pass; no PDF or viewer assets shipped.
- Non-GUI library ECJ compilation: zero errors.
- Native database regression checks: 1393 passed; previous guidance/variant 56
  and Volvo 38 passed.
- Java syntax, XML, resource labels and `git diff --check` validation.
- Added Android packaging test for native data/year options and adapted report
  test for year-specific output. Android APK/Gradle/device UI tests not executed
  here; native screen/rotation/keyboard behaviour still requires device testing.
