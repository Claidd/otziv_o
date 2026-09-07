# Android native verification, 2026-09-07

This records a real debug APK installation and native plugin checks on an isolated, disposable Android emulator. It is not a production-signed release certification, a real-device check, an iOS check or proof of authenticated production workflows.

## Toolchain and corrections

The installed SDK resolves from `%LOCALAPPDATA%/Android/Sdk` (a local junction may resolve it to another drive). The old ignored `mobile/android/local.properties` pointed to another user's SDK. Its original bytes were saved under `.codex-tmp/native-android-20260907/local.properties.before`, and the local SDK path was corrected. Android generated files had no pre-existing tracked diff and their sync was coordinated with the client-code owner. Only `cap sync android` was run; iOS generated files were not synced.

The old `android-env.ps1` unconditionally replaced JAVA_HOME with a nonexistent user-specific Temurin path. It now honors `OTZIV_ANDROID_JAVA_HOME`, checks the JDK and SDK, and detects JDK 21 under the user's `.jdks` directory. Invalid explicit paths fail closed. `build-android-debug.ps1` now propagates a Gradle failure instead of continuing after a nonzero native-command exit.

Gradle 8.14.3 can run on JDK 24 according to [Gradle's compatibility matrix](https://docs.gradle.org/current/userguide/compatibility.html), but the installed Capacitor Camera build requests an **exact JDK 21 compiler toolchain**. An actual first build on the installed Corretto 24 failed for that reason. The environment preflight therefore requires JDK 21 for this project; no plugin toolchain was weakened to make it compile.

A portable Temurin `21.0.12.1+1` was obtained from the [official Adoptium release](https://github.com/adoptium/temurin21-binaries/releases/tag/jdk-21.0.12.1%2B1), after comparing the archive with the publisher's checksum.

Archive SHA-256: `f9d6e191ab098c0d416e7d588a24420a8621cd2f4720dab2459b8b7b2d2d8b4e`.

It was extracted into the task's ignored evidence directory; no system Java installation/default or machine environment variable was changed. Gradle installed its required Build-Tools 35 and Android Platform 36 packages through the existing SDK's already accepted licenses.

Rebuild with a configured JDK 21 and the real installed SDK:

```powershell
$env:OTZIV_ANDROID_JAVA_HOME = 'C:/path/to/installed-jdk-21'
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
Set-Location mobile
npm run build:local
if ($LASTEXITCODE -ne 0) { throw 'Web build failed' }
./node_modules/.bin/cap.cmd sync android
if ($LASTEXITCODE -ne 0) { throw 'Android sync failed' }
./scripts/build-android-debug.ps1
```

The debug build initially logged `Cleartext HTTP traffic to localhost not permitted`. Local mobile configuration intentionally uses `http://localhost:8088` over adb reverse. A **debug-source-set-only** network security config now permits cleartext for exact `localhost` and `127.0.0.1`; its base policy denies other cleartext domains. Release TLS policy remains unchanged. The native Capacitor HTTP client passed a loopback fixture request and rejected another cleartext domain with the expected policy error.

## Actual emulator checks

The installed image was Android API 36 Google Play x86_64, emulator 36.5.10.0, with Windows Hypervisor Platform available. The pre-existing `Medium_Phone_API_36.0` device was not launched or wiped. A new `OtzivDisposable` AVD used a copy of hardware configuration only and newly created data under the task's own directory. No existing userdata, accounts, keystore or snapshot was copied into it. AVD/emulator home environment variables were scoped to the launch process.

The emulator ran hidden with `-no-window -no-audio -no-snapshot -no-metrics`, two CPUs and 2 GiB memory. Radio and Wi-Fi user-mode options included `restrict=on,ipv6=off`; [QEMU documents `restrict`](https://www.qemu.org/docs/master/system/qemu-manpage.html) as preventing guest access to the host/outside network. This emulator version additionally uses netsim Wi-Fi, so the actual child process was checked for `--debug-no-network --debug-no-guest-to-host-mdns --no-web-ui --no-cli-ui`. Relying on the older QEMU options alone would not prove the netsim boundary. No provider login or message was performed. Local fixture/API access uses explicit adb reverse instead of opening guest Internet access.

Only the owned serial `emulator-5580` was targeted, after checking `adb -s emulator-5580 emu avd name`. No real device was connected or modified. A fresh APK was installed, then the updated local-policy APK was installed as a synthetic versionCode 62→63 upgrade after both APK signatures were verified and their certificate digests compared. Version overrides were process-scoped Gradle properties; the repository's release version remains unchanged.

Verified observations:

- First debug Gradle build: success, 489 tasks (415 executed), JDK 21. The policy-update build passed with 15 tasks executed.
- Real app cold launch succeeded on API 36. Its actual WebView reported `platform: android` and rendered the login screen. Network isolation produced the expected offline banner.
- The actual SecureStorage native bridge wrote and read a synthetic value. The app's shared-preferences file did not contain that plaintext value. This is a focused storage check, not a complete cryptographic audit.
- The value survived the compatible APK upgrade and resulting process restart, then native removal/readback confirmed deletion. No real token was used.
- The native HTTP bridge reached a local mock endpoint over adb reverse and blocked cleartext to a non-loopback domain. This proves the debug policy behavior independently of backend readiness.
- An actual Android VIEW intent with an unsolicited `otziv://auth/callback` code/state returned to the login screen with a Keycloak verification error. It did not create an authenticated UI session; this is a native callback negative case, not proof of a successful OIDC exchange.
- Existing Android release-verifier contract checks passed all eight cases, including wrong signer/package/version, debuggable APK rejection and staging cleanup. The environment rejected JDK 24 and a missing explicit JDK path before invoking Gradle.

The original debug APK SHA-256 was `000fd113c74f0fc787452d8c40bf76fd569b73a8e9dfb63c2b27a9176e2de100`. The first local-policy fixture APK was `6e5ec880c32f7ce7478577ffad1864e6627a2ddb192971a3d888bbcacd0d62d`. The APK with lint corrections passed the native storage and HTTP-policy probes. After the mobile source checkpoint at 07:24:22 UTC, `build:local`, `cap sync android` and debug Gradle build passed again. That checkpoint APK is **`706e3b1cefecf40dbbd49f9508035fc476839933e4415efe50b5e4f90c52a16a`**, with debug certificate SHA-256 `1997304441e670b60881268766ea1e2f14c681d279f21938c4ebe7c5f649f07c`. That checkpoint APK was installed after certificate comparison; native storage write/read, process-restart persistence/removal and unsolicited callback rejection passed again. These are debug artifacts and must not be published as signed production releases.

On that checkpoint APK, the actual Capacitor HTTP bridge reached the healthy local backend over `adb reverse tcp:8088 tcp:8088`: `/actuator/health` returned HTTP 200 and `UP`. Keycloak discovery returned HTTP 200, its issuer/authorization/token endpoints all used the expected local origin, and it advertised PKCE `S256`. The health probe explicitly parsed the actuator vendor-JSON response when the bridge returned its body as a string. No authenticated session or production endpoint was used.

After the navigation source freeze at 08:07 UTC, another compatible fixture APK was built and installed: `a59a41b222a4cd3973f3096c0ad9fa0ffd7731392c0ea0e77f297cf6485bbafc`. Native backend health/Keycloak discovery, settled-write storage persistence/removal and unsolicited callback rejection passed on this APK. An immediate force-stop directly after an awaited SecureStorage write lost the synthetic value; a settled write survived. This exposed an actual missing disk-commit acknowledgment and triggered the correction below. The earlier settled-write observations do not prove crash durability.

## Android auth disk barrier

The pinned SecureStorage Android implementation and Capacitor Preferences use `SharedPreferences.Editor.apply()`. Its completion updates memory and queues disk I/O. [Android documents](https://developer.android.com/reference/android/content/SharedPreferences.Editor#apply()) that a subsequent `commit()` on the same store waits for outstanding `apply()` writes; the [AOSP implementation](https://android.googlesource.com/platform/frameworks/base/+/main/core/java/android/app/SharedPreferencesImpl.java) also commits the current memory generation for a no-op editor.

`AuthStorageDurabilityPlugin` runs this barrier off the UI thread, in a bounded serial executor. Only the selectors `preferences` and `secure` are accepted, mapped to the two pinned library files. It rejects a false commit result, interruption, unsupported selector or unavailable executor. It accepts no arbitrary path, preference name, key or value and returns no stored data. Four native JVM unit cases cover the allowlist, failure and interruption contracts; JDK 21 compilation and `testDebugUnitTest` passed.

The Android auth storage service serializes public token/PKCE operations and awaits the barrier after each relevant mutation. Token replacement and logout retain their revocation marker until the secure and legacy stores are committed. A failure reaches the caller instead of publishing a successful persistent session; refresh state is published only after committed storage and a current-session check. This is an ordered protocol for two stores, not an atomic cross-file transaction or a multiprocess guarantee. iOS persistence has a separate implementation and is not certified by this Android test.

A reproducible owned-emulator test is `mobile/scripts/native-auth-storage-smoke.mjs`. It checks the AVD name, fixture app version and installed APK hash before touching synthetic auth state. It exercises the actual development-build Angular auth/storage services through the native WebView, immediate process death after commit, and an interrupted local logout after the marker reaches disk but before the secure overwrite. It does not use production credentials or perform provider login. Run only after installing the intended same-key debug fixture:

```powershell
node mobile/scripts/native-auth-storage-smoke.mjs --adb=C:/path/to/Android/Sdk/platform-tools/adb.exe --serial=emulator-5580 --avd=OtzivDisposable --apk-sha256=EXPECTED_SHA256 --evidence=E:/path/to/owned-evidence
```

The CI Android job runs native unit tests, lint and debug assembly and retains the reports. The final rebuilt APK has SHA-256 `b24395b37ad75b1107136eae51c6c3c6eb1cbd84d81956763487a656a7e10b85`. Native unit tests, lint and assembly passed at 09:02 UTC. Its certificate matched the preceding owned fixture, and the hash of the actual installed APK was checked after the compatible update.

All **nine actual-emulator auth durability checks passed at 09:03:53 UTC**. A committed synthetic value survived immediate process death; completed local session clearing survived restart without restoring tokens. The interrupted-clear case verified that the revocation marker was already on disk while the earlier encrypted fixture was still present, then cold-started the actual application and confirmed that the marker prevented token restoration. The plugin rejected an arbitrary preference-file selector. These checks exercise local persistence and recovery, not a successful provider login or server-side logout.

On the same final APK, the native HTTP bridge again obtained local backend health `UP` and Keycloak discovery with the expected origin and PKCE `S256`; an unsolicited callback remained rejected. Synthetic token, marker and storage-fixture removal were checked before the serial/name-checked emulator shutdown. Results, installed-artifact metadata and cleanup evidence are retained under `.codex-tmp/native-android-20260907/auth-durability-final*`.

An additional actual `lintDebug` run found 13 errors and 19 warnings. Eleven errors concerned API-30 calls inside private diagnostics helpers: their caller already returns before use on older versions, and `@RequiresApi(R)` contracts now express that existing requirement. The local SDK property now escapes its drive-letter colon, and the manifest explicitly marks camera/autofocus hardware optional because file attachment can also use existing files. No blanket lint suppression/baseline or minimum-SDK increase was added. `lintDebug assembleDebug` then passed with **0 errors and 19 warnings**. Warnings about selected-photo access on Android 14, resources/icons, manifest ordering and available Gradle updates remain visible; a passing lint task is not zero-warning certification.

`processReleaseMainManifest` also passed. The actual merged release manifest had no debug network-security config, enabled cleartext flag or enabled debuggable flag. This was manifest verification, not a signed release build.

Raw logs, launch arguments, probe scripts and result JSON are under `.codex-tmp/native-android-20260907/`. Archive relevant evidence outside that temporary directory if long-term retention is required. Toolchain/emulator availability and local artifact success are distinct from device/provider acceptance.

The owned emulator was stopped with a serial/name-checked `emu kill`; subsequent checks found no owned emulator, QEMU or netsim process or connected serial. Automatic approval review rejected recursive removal of the owned `avds` and `emulator-profile` directories despite path-containment checks. Those stopped test directories are retained with the evidence; no alternative deletion method was attempted. The pre-existing AVD was not modified.

## Remaining acceptance

Authenticated OIDC/PKCE login/logout, revoked-session refresh, photo/camera workflows, production push notifications, background/foreground behavior, production-signed upgrade and iOS keychain/ATS remain separate acceptance checks. A same-key debug fixture upgrade does not validate the distribution signing key or every historical production APK. No actual production credential was requested or used for this local proof.

The local native health/discovery checks above establish transport and unauthenticated configuration access. An APK build, successful discovery or an unauthenticated login screen does not prove the authenticated workflows listed here.

A subsequent local authenticated-flow preflight was stopped by automatic approval review: it rejected the command containing an Android Chrome VIEW intent for the loopback health URL and read-only devtools inventory, reporting only `blocked by policy`. No alternative execution path was used. No Keycloak/database fixture user or authenticated session had been created, and the named owned emulator was stopped normally afterward. This is a tool-policy limitation of this verification run, not evidence of an application login defect. The exact attempted scope and cleanup are recorded in `oidc-acceptance-blocker.json` under the evidence directory.
