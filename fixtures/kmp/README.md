# Small executable KMP controls

This standalone project uses the repository wrapper, Kotlin 2.4.21, AGP 9.3.1,
JDK 25, JVM bytecode 17, Android compileSdk 37 / Build Tools 36.0.0 / minSdk 24.
It is **preview test infrastructure**, not a qualification corpus for all rules.
The Android plugin requires a provisioned SDK even when configuring other targets.
The checker's engine/broker still uses JVM 21; platform CI installs that separate
toolchain before selecting JVM 25 for project Gradle/compiler execution. Provider
drivers receive the observed project `JAVA_HOME`, not their broker's `java.home`.

From the repository root:

```sh
./gradlew -p fixtures/kmp desktopTest fixtureAndroidHostTest lint --max-workers=2
# On a compatible arm64 Mac with explicitly selected Xcode 26.4.1 / 17E202:
python scripts/ci/run_platform_checks.py --profile ios --output build/ci-evidence/ios.json
```

The common boundary tests run against each selected platform's actual code.
Android tests are **host-side JVM tests**, not emulator/device execution.
The Apple lane must compile both iOS arm64 targets, link both frameworks, and
execute `iosSimulatorArm64Test` on the recorded iOS 26.4 simulator. A non-Darwin
host is unavailable for that lane; it cannot substitute JVM tests for iOS.

Controlled invalid variants are opt-in, outside default source directories:

| Property | Expected failure |
| --- | --- |
| `-PfixtureNegativeCase=missing-actual` | No iOS actual for the common `platformFamily` expect |
| `-PfixtureNegativeCase=wrong-actual` | Incompatible iOS actual return type |
| `-PfixtureNegativeCase=new-api` | Android Lint `NewApi` for unguarded API 26 use at minSdk 24 |

The harness requires a failed task **and the expected diagnostic**. Network,
configuration, missing SDK, timeout, or disabled-target failures are not positive
diagnostic controls. It forces task execution rather than trusting cached reports.
The optional `fixtureBuildDir` is for an owner-trusted harness to relocate outputs
outside an immutable source capture. `fixtureSimulatorDevice` selects the recorded
simulator UUID through KGP's supported `device` property.
