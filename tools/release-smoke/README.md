# Release class verification on Android

Run from the repository root with a USB-debugging device connected:

```powershell
./tools/release-smoke/verify-release-classes.ps1 `
  -Apk app/build/outputs/apk/release/app-release-unsigned.apk `
  -Mapping app/build/outputs/mapping/release/mapping.txt `
  -Serial <adb-device-serial>
```

The script checks that the APK's R8 map ID matches the mapping, resolves the two
WebSocket classes' obfuscated names, then loads them from the actual release DEX
using Android's `dalvikvm -Xverify:all`. It initializes the classes and inspects
their declared methods and constructors; it does not construct a server or call
application entry points. Review static initialization before using the optional
`-Classes` argument for other classes.

No APK is installed, no Activity is launched, and app data is not accessed. The
installed app version does not affect this test. The script places its verifier
and DEX archive in a unique `/data/local/tmp/autonion-r8-smoke-<id>` directory and
removes those two files and the directory afterward. Logs remain under
`build/release-smoke/<id>/verification.log` in the repository.

The default SDK, JDK and build-tools locations match this Windows project setup;
override `-Sdk`, `-Jdk` or `-BuildTools` as needed. A nonzero process exit or a
mapping mismatch fails the check. Preserve each released APK/AAB with its own
mapping; a mapping from another build is not interchangeable.

This is a focused regression check for the startup verifier failure. It does not
test Activity startup, server connections, resource delivery, native libraries,
backup restoration or other features. Run the signed optimized release through
the manual matrix in [the release testing guide](../../docs/r8-release-testing.md)
and confirm the Play-delivered build in internal testing.
