# Small executable KMP controls

This standalone project uses the repository wrapper, Kotlin 2.4.21, AGP 9.3.1,
JDK 25, JVM bytecode 17, Android compileSdk 37 / Build Tools 36.0.0 / minSdk 24.
It is **preview test infrastructure**, not a qualification corpus for all rules.
The Android plugin requires a provisioned SDK even when configuring other targets.
The exact stable SDK package is **`platforms;android-37.0`** (directory
`platforms/android-37.0`), not the nonexistent `platforms;android-37` identifier.
The official repository lists API major **37**, minor **0**, revision **2**,
extension **22**, and `base-extension=true`; 37.1/37.2 are not substitutes.
`scripts/ci/provision_android.py` records actual properties and `android.jar`
digests and rejects preview/wrong-minor packages. It does not accept licenses
automatically or establish a whole-distribution hash lock. The authored AGP
`compileSdk = 37` DSL and candidate Lint task paths still need a genuine successful
AGP-9.3.1 run against this exact package before their feasibility is claimed.
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

The same Apple CI runner also attempts the separate Native semantic/FIR **library
feasibility** driver after target controls. It discovers exactly one already
provisioned macOS arm64 Kotlin/Native 2.4.21 home, selects the observed project
JDK 25, disables Native dependency auto-download, and uploads only validated
metadata from the driver's private `summary.json`. Android configuration and a
simulator runtime are not prerequisites for that separate library gate. Missing
existing LLVM/libffi/sysroot inputs remain unavailable/failed; host Xcode build
17E202 does not attest Native's configured `xcode_26.4_17E192` sysroot. This does
not replace the real framework/link/simulator requirements above or qualify any
custom rule.
