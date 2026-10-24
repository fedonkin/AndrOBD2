# Diagnostic tools: implementation and validation

## Available in this change

- Main menu → **Scan all modules**: sequential read requests using the currently selected vehicle profile. Per-module results distinguish a received DTC response from no confirmed response. Demo is explicit and separate. The dialog can be closed to cancel; a 180-second overall watchdog prevents an indefinitely waiting UI. Recording must be stopped before scanning.
- Main menu → **CSV logs and charts**: Android document picker, ten recent document URIs, up to four selected numeric channels, independent chart scales, touch readout and MARK navigation. The viewer does not change the active protocol service or PID selection. Read permission expiry is reported instead of silently returning an empty chart.
- CSV import: UTF-8 BOM, semicolon/comma/tab delimiters, decimal comma, quoted fields (including escaped quotes), midnight rollover. Limits: 8 MiB, 20,000 rows, 192 columns and 1,000,000 cells. Malformed row widths and multiline/unclosed quotes are explicitly rejected. This is a viewer for telemetry tables, not a general spreadsheet importer.
- Stop/export dialog and compact summary include **preliminary observations**. Missing context suppresses idle diagnosis. Explicit Fahrenheit headers are normalized. No pressure diagnosis is issued without knowing the pressure reference and engine specification.
- Live rows show indicative fuel-trim, temperature and voltage observations. OEM channel classification stays inside `vehicles/jaguar` and `vehicles/kia` UI packages and is used only for the active profile.
- `ATRV` is interleaved at approximately 2.5-second intervals. Unsupported replies disable it for the connection. This measures voltage at the adapter, not directly at the battery; it is **not fast enough to guarantee capturing starter minimum voltage**.
- Virtual channels: arithmetic STFT + LTFT for each bank, adapter voltage, approximate gasoline MAF-based L/h and L/100 km. Source values expire for calculation purposes after ten seconds. Fixed-PID selections include calculation dependencies; virtual PID numbers are never sent as physical requests.
- Gasoline estimation requires Mode 01 PID 51 = gasoline and fuel-system status = closed loop. Unknown fuel, diesel and open-loop fuel-cut/enrichment are not estimated using gasoline assumptions. L/100 km is undefined below 4 km/h, not zero. Assumptions: AFR 14.7 and density 745 g/L; this is not a calibrated fuel meter and does not support ethanol blends accurately.

## Deliberately not enabled / remaining scope

- New Jaguar selector, A/C, EVAP/IAC and misfire Mode 22 definitions require verified raw responses, engine/ECU calibration and documented byte formulas. A positive response alone does not validate an interpretation. No guessed addresses were added.
- A universal “ideal idle MAF” or VE diagnostic cannot be derived reliably from displacement and RPM alone. MAP, IAT, engine configuration and operating conditions are needed. No MAF replacement verdict is generated.
- Trip-average fuel consumption is not yet implemented. Instant estimates must first be validated against fuel type, fuel cut and sampling gaps.
- Automatic VVT, torque-converter and thermostat health certification is not implemented: observed values are not proof of mechanical health.
- The OEM counter (including Jaguar 220200) is a prompt to read actual DTCs, not proof of a specific number of hidden faults. Incomplete readiness does not prove P1000 is stored.
- Profile-specific configurable thresholds and sustained-event detection remain future work. Current live indicators are approximate individual-reading flags, not fault codes.

## Validation performed in the sandbox

- Parsed project Java sources with javalang and resource XML with ElementTree; checked newly referenced resources and `git diff --check`.
- Compiled the non-GUI `library` source set with Eclipse ECJ 3.16 (`-8`, no annotation processing) on a JDK4Py runtime: zero compilation errors. This supplemental compatibility check is not the Gradle Java 17 / Android release build.
- Compiled `LiveSessionAnalyzer`, `LiveHealthEvaluator` and `CsvLogParser` separately. CSV smoke harness used the repository's real AChartEngine JAR and a JVM-only stand-in for the immutable chart marker DTO; Android drawing was not exercised.
- Executed 18 smoke assertions covering voltage parsing, trim prerequisites/reset, gasoline gating, stationary consumption, CSV midnight/markers/decimals/malformed rows, qualified analysis, missing-context behavior, Fahrenheit, demo module scan and standard-profile gating.
- Executed a simulated real-protocol NODATA sweep: six X200 profile modules, 48 adapter prompts, completed with every module marked as having no confirmed response.
- Added JUnit regression sources for calculator, CSV, analysis and module scanning. The Gradle JUnit suites and APK build were **not** run: Android SDK/Gradle dependencies are not available in this sandbox. Android lifecycle/UI and real adapter behavior still require device testing.

## Device acceptance checklist

1. Build with the existing Windows `build_apk.bat`; verify debug and release variants.
2. Standard profile: no OEM scan, real connection never starts demo.
3. Jaguar and KIA: read-only full scan; test unsupported module, adapter disconnect, cancellation, timeout, then return to Live Data. No automatic DTC clearing.
4. Live Data: check ATRV against a multimeter, STFT/LTFT arithmetic, unsupported gasoline/fuel-status behavior and stale calculations.
5. Record, stop and export; verify the observations are labeled preliminary and that markers remain in CSV and smart summary.
6. Open CSV through the system picker; choose four channels, scrub, jump to MARK, rotate device, reopen from recent files and revoke file access. Check 320dp portrait, landscape, day/night, and enlarged font.
7. Compare estimated fuel rate with a validated reference before relying on it for trip costs.

## Volvo XC90 I / II (experimental)

Two generation-specific profiles, a small EN/RU Volvo P-code reference, and
read-only experimental CAN Mode 22 candidates are documented in
[volvo-xc90.md](volvo-xc90.md). This is not full VIDA/D2/DoIP coverage;
read the generation, transport and source-validation limits before use.
