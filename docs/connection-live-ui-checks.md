# Connection and Live Data row changes

## Changes
- Wi-Fi: one socket/reader/writer per connection attempt. Cancelling invalidates the attempt before closing its socket. Late completion cannot publish CONNECTED or fail a replacement. CONNECTED is published before reader startup so immediate EOF is reported. Stop closes the socket and shuts down its TX executor.
- Bluetooth Classic: reject obsolete connection completions and worker failures; use fresh streams after reconnect; restore the protocol writer; fail if obtaining socket streams fails. The adapter settling delay no longer holds the service lock. Immediate EOF is reported after CONNECTED.
- BLE: serialize GATT callbacks with connect/stop and reject events from old GATT objects. Handle descriptor/write errors, unsuccessful discovery, null connectGatt and permission failures. Bound initial connection/service discovery to 10 seconds. The existing CCCD compatibility fallback is retained and still needs testing with physical adapters.
- UI: filter stale device-name/toast messages as well as state notifications from replaced transport instances. Demo/real-connection selection and recorder lifecycle are unchanged.
- Live rows: ALERT = red background/value/border and readable reason; WARN = amber; normal/unassessed = usual background. Selection retains its blue outline. Rebinding resets severity, including missing/non-finite values. Light/dark resources are used.
- Labels and reasons wrap instead of being ellipsized. Favorites have a 48dp target. Long values and enlarged system text use the wide-value layout. Invalid conversion-array indexes no longer crash health evaluation before formatting.
- Existing screening thresholds are retained, not presented as manufacturer limits or proof of a failed component. Unknown units are not assumed to be Celsius, volts or percent. No color also means “not assessed”, not necessarily “healthy”.

## Executed checks
- 7 JUnit tests: four network scenarios (real loopback TCP plus controlled late socket failure) and three health-evaluation groups.
- Existing recorder/CSV regression suite: 10 JUnit tests passed; Jaguar/guidance/Volvo suites: 1393 + 56 + 38 checks passed.
- 7 supplemental Bluetooth checks using fake Android sockets: cancelled attempt/replacement and immediate EOF. This is not a real Bluetooth stack test.
- Non-GUI library compilation, plus the four transport/service classes compiled against Android API 35 android.jar using ECJ (Java 8 source compatibility). This supplements, not replaces, the project's Java 17 / API 37 Gradle build. Minimal AndroidX annotation/UI-constant stubs were used in the transport compilation; minimal Android scaffolding was used for JVM transport tests.

## Device validation still required
Build with build_apk.bat, then test on the actual phone/adapter:
1. Real connect, cancel during connection, reconnect immediately; old errors/names must not replace the current session.
2. Remove adapter power, reconnect while recording: retain the same recording session and markers.
3. Repeat for Classic Bluetooth, BLE (including service discovery/CCCD), Wi-Fi. Check permission denial/revocation. USB behavior was not changed and needs its own hardware test.
4. Run LiveRowHighlightTest: red → amber → ordinary → missing value on one recycled row, in both themes at 150% font scale. Check long Russian labels and favorites on a small screen.
5. Check warnings with engine operating context. Low voltage during cranking and transient fuel corrections must not be mistaken for a confirmed component failure.

No APK, instrumentation/UI test, physical adapter, or vehicle validation was run in the sandbox.
