# Jaguar S-TYPE: possible-cause guidance, not a full PDF implementation

## Implemented scope

17 DTC entries have EN/RU possible-cause guidance:
P0102, P0103, P0116, P0125, P0135, P0141, P0155, P0161,
P0171, P0172, P0174, P0175, P0180, P0190, P0192, P0193, P0196.

The guidance is shown under the fault description (unlimited wrapping), copied
into the diagnostic report and included in the all-module scan report. It is
looked up on display/export, not permanently appended to the stored DTC, so it
cannot remain attached after switching to a different brand. Existing long-press
web search is unchanged.

Source: Jaguar **Powertrain DTC Summaries, MY2001, VIN L86902 onward**,
[standalone P20001](http://www.jagrepair.com/images/AutoRepairPhotos/P20001.PDF),
possible-cause tables for the explicitly listed DTCs. The supplied
[combined STYPE document](https://www.jcna.com/sites/default/files/STYPE.pdf)
explicitly identifies its 2001–2002 section as applying from VIN L86902.
Descriptions are paraphrased/translated and identify the document scope.

Only X200 and Auto Jaguar profiles expose this conditional reference; every
entry says **MY2001–2002 / VIN L86902 onward — verify ECU applicability**. Auto
selection is not evidence of model year. Later X202 profiles are excluded.
No guidance is generated for an unknown DTC or for a brand that has no entry.
No DTC is inferred from a single live value, and no part is declared faulty merely
because it occurs in the possible-cause column.

For example, the source lists EFT among possible incorrect PCM inputs for
P0172/P0175. This is reason to investigate its signal alongside other causes,
not proof that fuel temperature caused the rich mixture. P0180 guidance includes
connector, wiring resistance/open/short and the sensor itself. For P0196 the
source's fallback is stated explicitly: V6 CHT / V8 ECT. No new unverified fuel
temperature PID or formula has been introduced.

## Not implemented from the full PDF

This change does **not** claim to implement every row or every capability:
- Remaining engine, transmission, chassis and body DTC possible-cause tables.
- Full per-model-year / VIN / engine / ECU-variant applicability selection.
- Structured monitoring conditions, MIL trip rules, default actions and drive
  cycles for all DTCs (P0196 fallback is included as a limited sourced example).
- PDU/WDS KOEO/KOER self-test commands, actuator tests, or unknown OEM PIDs.

The PDF is primarily a diagnostic reference, not a table of ELM327 wire commands.
A DTC marked JAG does not imply it can be retrieved by generic Mode 03. Do not
convert descriptive entries into guessed diagnostic requests.

## Validation

Non-GUI library ECJ compilation: zero errors. Standalone JVM regression checks:
56 guidance/variant assertions plus 38 existing Volvo assertions passed.
A JUnit report-export regression was added, but the Android Gradle/JUnit runner,
APK build and device UI checks were not available in this sandbox. In particular,
verify long, wrapped fault cards and copied reports on a narrow phone screen.
