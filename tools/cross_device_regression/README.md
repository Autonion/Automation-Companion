# Cross-device regression checks

Run `python tools/cross_device_regression/run.py` from the Android repository.
The runner compiles the production discovery, connection, pairing, repository, and desktop
flow classes using the Kotlin, coroutines, and Gson versions in the local Gradle
cache. Set `JAVA_HOME` and optionally `GRADLE_USER_HOME` for a different installation.
Compiler output and results are written under the ignored `build/cross-device-regression` directory.

The tests simulate socket callbacks and use in-memory Android platform doubles.
They exercise authentication ordering, graceful closure, obsolete callbacks,
pending cancellation, endpoint changes, retry ownership, toggles, invalid
endpoints, empty close frames, socket creation failures, per-agent credential migration/revocation,
and flow ownership/transaction matching. It also verifies saved paired endpoint
restoration, preserved deselection, network-triggered NSD restart, silent empty
scan recovery, preservation of healthy sockets/PIN pairing during refresh, and
flow loading from an authenticated pre-login helper, and preservation of the
authenticated connection mode against stale discovery metadata. All 20 scenarios pass.
They do not invoke Windows unlock or
access real credentials.

The runner also writes `build/cross-device-regression/gson-client-info.json` from
the production Android identity encoder with a synthetic token. The Agent's
`windows/unlock_helper/testdata/gson-client-info.json` contains this wire fixture.
Its native `prelogin_wire_test.cpp` runs the actual helper connection handler over
loopback, using isolated synthetic persisted trust and flow metadata. Run the
Agent's `tools/run_connection_native_tests.ps1` to check JSON decoding,
authentication, flow retrieval without the app, and revocation across that boundary.

Also run `./gradlew.bat :app:testDebugUnitTest --tests "*cross_device_automation*"`
to verify the real Android/OkHttp API compilation and the Gradle unit tests.
Physical-device Wi-Fi loss, OS discovery callbacks, and sleep/resume still require
device testing. Desktop loopback and discovery regression tests live in the Agent
repository at `test/connection_recovery_test.dart`.
