# Startup crash fix (2026-10-24)

## Confirmed launch failure

The supplied AndroidRuntime report repeatedly shows:

```
ClassCastException: java.lang.Integer cannot be cast to java.lang.String
  ObdItemAdapter.getMatchingItems
  ObdItemAdapter.getPreferredItems
  ObdItemAdapter.setPvList
  MainActivity.onSharedPreferenceChanged
  MainActivity.onCreate
```

`ObdProt` seeds `PidPvs` and `VidPvs` with an **Integer 0** placeholder in its constructor. When there is no saved data-item selection, the adapter passed the raw PV map's key set as a `Set<String>` default to `getStringSet`. Iterating it as `for (String key : ...)` inserted a runtime cast and crashed before startup finished. This is not evidence of corrupted preferences, and deleting application data is not required.

The adapter now requests a null preference default. `PvItemSelection` copies native values when no selection exists and performs lookup using unmodified key types for explicit selections. An explicitly empty selection remains distinct from an absent preference. Existing string PID selections and brand/virtual-channel additions are preserved.

## Separate older gauge crash

The same report includes earlier AndrOBD failures from `AwesomeSpeedometer.setSpeedometerColor` → `RadialGradient`: `ending radius must be > 0`.

Checked upstream SpeedView **1.6.1** source: the setter calls `updateGradient()` immediately with radius `sizePa * 0.5f`. RecyclerView binding can precede measurement. Each holder now retains the latest requested color and applies it only when `getSizePa() > 0`, either immediately on an already sized holder or from one holder-owned layout listener. This avoids both zero-radius creation during bind and stale colors from multiple queued bind callbacks.

The report also contains a TransactionTooLargeException from a different application. It is not attributed to AndrOBD.

## Validation

- Reproduced the original Integer-to-String exception in a JVM harness with a mixed-key map.
- Compiled and executed the production selection helper in that harness: default selection, saved string selection, explicit empty selection, native integer selection, missing PID and no input mutation all passed.
- Added JUnit selection regression tests and an Android instrumentation test for pre-layout color binding, latest-color selection and rebinding a measured gauge.
- Java syntax checks and `git diff --check` passed.
- Android APK build and device/instrumentation execution were not available in this sandbox. Rebuild and update the installed APK **without clearing app data**, then verify cold launch and Dashboard in portrait/landscape.
