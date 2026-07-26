# Project record

## 1. Purpose and scope

R Installer Next replaces the useful behavior of the outdated `R-安装组件`
with a maintainable, clean-room implementation for current Android. The primary
requirements are:

- run correctly on Android 16;
- use Shizuku rather than root or hidden framework APIs;
- allow the device owner to add newer source apps such as 应用宝
  (`com.tencent.android.qqdownloader`);
- silently install requests from approved, cryptographically pinned sources;
- require confirmation when a source cannot be verified; and
- accept both ordinary APKs and common split-package archive formats.

The new application ID is `dev.jpeng.rinstaller`. The current version is
`0.4.0` (`versionCode` 4), and its localized launcher name is
**R-安装组件** / **R-Installation components**.

## 2. Clean-room statement and legacy audit

No source code, resources, signing material, or decompiled implementation from
`R-安装组件` was copied into this repository. The installed APK was read as an
external compatibility reference. Only public package metadata, Android
manifest declarations, and externally visible behavior were recorded.

Legacy builds inspected as external compatibility references:

| Property | Connected-device build | Downloaded reference build |
| --- | --- | --- |
| App/package | `R-安装组件` / `com.yxer.packageinstalles` | Same |
| Version | `2.6.9-beta` (`versionCode` 344) | `2.6.11-beta` (`versionCode` 350) |
| Target / compile SDK | 30 / 32 | 30 / 34 |
| Last known update | 2024 | 2024 |
| Public installer activity | `InstallActivity` | `InstallActivity` |
| Public inputs | `VIEW`, `INSTALL_PACKAGE`, `SEND`, `SEND_MULTIPLE`; APK, ZIP, file and content URIs | Same, including no-MIME `INSTALL_PACKAGE` and vendor MIME aliases such as `application/apk.1` |
| Privilege integration | Shizuku provider | Shizuku provider |

The downloaded `2.6.11-beta` reference had SHA-256
`f97f3c950f923add902733e581c65139e17fc3f13ad012a0543a2307008ee009`.
Its APK v2 signature verified successfully; the signer certificate SHA-256 was
`76a56fcc2086f161b3877075e4ec64bc91e7abe808b1139bd2d7950e59e31cf8`.

Local legacy APK copies and generated audit text are kept outside the repository
or under `.local-reference/`, which is ignored by Git. They are not
redistributed.

## 3. Toolchain and dependencies

| Component | Version/configuration |
| --- | --- |
| Android Gradle Plugin | 9.3.1 |
| Gradle wrapper | 9.6.1 |
| Java | 17 |
| `compileSdk` / `targetSdk` | 36 / 36 |
| Minimum SDK | 28 |
| Shizuku API/provider | 13.1.5 |
| Test framework | JUnit 4.13.2 |

Dependency resolution is limited to Google Maven, Maven Central, and the Gradle
Plugin Portal. The Shizuku API and provider libraries are the only runtime
third-party dependencies.

## 4. Architecture

### Main process

- `MainActivity` displays Shizuku state, opens the document picker, and opens
  the trusted-source manager. Its settings menu lets the owner choose System
  default, English, or Simplified Chinese.
- `AppLanguage` uses Android's application-locale API on Android 13 and newer.
  On Android 9–12 it applies the same persisted choice through a localized
  configuration context, so language switching works across the supported
  API range.
- `TrustedSourcesActivity` lists installed packages and lets the owner approve
  or revoke them. It follows the legacy icon/name/package/switch row design,
  adds the missing full installed-app list, and searches displayed app names
  as well as package IDs.
- `TrustedStore` records a package name plus the SHA-256 digest of its signing
  certificate.
- `InstallActivity` receives install intents, resolves the caller, prepares
  payloads, evaluates policy, and displays confirmation when required.
- `CallerVerifier` accepts Android-provided launch identity, result-call
  identity, or ownership of every supplied content URI. It records referrer
  identity only for display and never treats it as verified.
- `PayloadPreparer` copies input into app-private cache and safely expands APK
  entries from APKS/APKM/XAPK/ZIP archives.
- `ShizukuBridge` binds the privileged process and transfers read-only file
  descriptors through AIDL.

### Shizuku user service

`PrivilegedInstallerService` runs under Shizuku as UID 2000 (`shell`). It never
receives filesystem paths from another app. It receives already-open,
read-only `ParcelFileDescriptor` objects and streams them directly to Android's
package-manager CLI.

- A single APK uses `pm install -S <bytes>` with stdin.
- A split set uses `pm install-create`, one `pm install-write` per APK, and
  `pm install-commit`.
- Failed split writes abandon their session.
- Package-manager operations have a three-minute completion timeout.
- Only replace, test-only, and explicitly selected downgrade flags are
  supported.

Samsung's Android 16 package-manager help advertises a literal `-` stdin path,
but the device parser rejects it as `Unknown option -`. The implementation
therefore supplies `-S` with no path, which selects stdin on the tested Samsung
build and AOSP's package-manager command implementation.

## 5. Silent-install policy

The policy is intentionally fail-closed:

| Check | Why it is required |
| --- | --- |
| Verified identity | Prevents a caller from claiming another package name. |
| Package on allowlist | Keeps control with the device owner. |
| Pinned certificate matches | Prevents a different signer from taking over an approved package name. |
| Shizuku ready and authorized | Ensures the operation actually runs inside the intended privilege boundary. |

All checks must pass. If one fails, the user sees the source, payload, identity
method, allowlist state, and an install button. Referrer strings and arbitrary
intent extras are not authorization signals.

### Identity methods and limits

- Android 14+ launch identity (`getLaunchedFromUid` /
  `getLaunchedFromPackage`) is preferred and cross-checked against the package
  manager.
- `getCallingPackage` is accepted when the source started the installer for a
  result.
- If all supplied payloads are `content://` URIs owned by the same installed
  UID, the provider owner is accepted. This ties trust to the component that
  actually supplies the bytes, but it does not prove which app initiated a
  secondary chooser flow.
- `android-app://` referrers are display-only because callers can influence
  them.
- On older Android releases or through chooser/download-provider handoffs,
  Android may not expose a usable source identity. Those requests correctly
  fall back to confirmation even if the originating store is allowlisted.

For the strongest result, a source app should launch the explicit installer
activity, grant the content URI, and on Android 14+ enable caller identity
sharing with `ActivityOptions.setShareIdentityEnabled(true)`.

## 6. Input and resource safety

Supported direct payloads are `.apk` files. Supported containers are `.apks`,
`.apkm`, `.xapk`, and `.zip`; only `.apk` members are extracted. Safeguards:

- private, per-request cache directory with randomized name;
- path components stripped and filenames sanitized;
- duplicate names made unique;
- no archive entry is written outside the private directory;
- at most 200 APK members;
- at most 4 GiB of expanded APK data per archive;
- all file descriptors and temporary data closed/deleted after completion;
- no shell interpolation—every CLI token is supplied as a separate
  `ProcessBuilder` argument.

## 7. Permissions and exported surface

The application requests `android.permission.QUERY_ALL_PACKAGES` for the
installed-app allowlist UI and declares
`android.permission.REQUEST_INSTALL_PACKAGES` for Android installer
compatibility. Its exported intent filters are what allow app stores to route
standard and vendor-style APK requests to it. The second permission does not
replace the app's Shizuku and trusted-source policy. The app does **not**
request storage, accessibility, device-admin, root, notification, or
background execution permissions.

`InstallActivity` is exported for install intents. `MainActivity` is exported
only as the launcher. `TrustedSourcesActivity` is internal. Shizuku's required
provider is exported with `INTERACT_ACROSS_USERS_FULL`, following Shizuku's
integration contract.

## 8. Physical-device validation

Validated on 2026-07-26:

| Property | Result |
| --- | --- |
| Device | Samsung SM-S928W |
| OS | Android 16, API 36 |
| Shizuku | 13.6.0, server UID 2000 |
| App install | Success |
| Shizuku authorization | “Allow all the time”; ready state confirmed |
| Legacy-style UI | Home cards and icon/name/package/switch manager visually checked against the installed 2.6.9-beta app |
| Language setting | Live switching verified for System default (`[]`), English (`[en]`), and Simplified Chinese (`[zh-CN]`); authorization state remained intact |
| Chinese install UI | Source, payload, downgrade, install, cancel, and error labels verified on the physical device |
| Trusted app picker | 应用宝 found as `com.tencent.android.qqdownloader` and certificate pinned |
| App-name search | Live displayed-name search returned `1Password`; unit coverage verifies partial and full Chinese-name searches for `应用宝` |
| Verified caller | Debug fixture resolved as `dev.jpeng.rinstaller.fixture` via OS caller identity |
| Silent single-APK reinstall | Success; 2,458,258 bytes streamed and package update completed |
| Confirmation screen | No system package-installer confirmation appeared for the trusted fixture |
| Unit tests | Silent policy requires all four authorization conditions |

The debug fixture exists only to make the identity-sharing and update path
deterministic. It is not part of the production application and should be
removed from a user device after validation.

### Android 14 routing regression

Validated on an AOSP Android 14 (API 34) emulator after a report that 应用宝's
installation flow did not detect R-安装组件:

| Intent shape | v0.3.0 before fix | v0.4.0 |
| --- | --- | --- |
| `INSTALL_PACKAGE`, `content://`, no MIME type | Not offered | `InstallActivity` resolves |
| `VIEW`, standard APK MIME type | Resolves | Resolves |
| `VIEW`, `application/apk.1` | Not offered | `InstallActivity` resolves |
| `VIEW`, `application/1` | Not offered | `InstallActivity` resolves |

The failure occurred before Shizuku or trusted-source policy evaluation:
Android had no matching intent filter through which to deliver these
vendor-style requests. Version 0.4.0 adds the missing no-MIME and vendor-MIME
filters. Requests whose caller identity is not verifiable still open the
confirmation screen instead of silently installing.

The debug fixture then exercised both added routes with a readable
`content://` APK. The no-MIME route offered R-安装组件 in Android's chooser and
the vendor-MIME route opened it directly; both reached the confirmation UI
with the payload name and size populated.

## 9. Build, test, and release

From the repository root:

```sh
export JAVA_HOME=/path/to/jdk-17
./gradlew clean testDebugUnitTest lintDebug assembleDebug
./gradlew assembleRelease
```

Debug output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The Gradle release output is unsigned by design. Public artifacts are aligned
and signed outside Gradle so no signing secret enters the repository.

First public release record:

| Property | Value |
| --- | --- |
| Release | `v0.3.0` |
| Source commit | `ef66064694b0d3ff2da82a08cf6d07e2ae1108b8` |
| Public page | <https://github.com/jpeng11/r-installation/releases/tag/v0.3.0> |
| APK | `R-Installation-v0.3.0.apk` |
| APK SHA-256 | `6a00acce22fd10238c39a31b5dd5f0bd21d77fe901171b336f8509616f090bc9` |
| Signature scheme | APK Signature Scheme v3 |
| Signer certificate SHA-256 | `0e2e1c077fff6d1bc97865c32489e203b078ca5620b9fd50ed1e886a9a483797` |
| Verification | `zipalign -c`, `apksigner verify`, metadata inspection, and anonymous GitHub re-download all passed |

The private release key is not committed or attached to the release. A private
maintainer backup exists outside the repository, and its password is stored in
the macOS Keychain entry `r-installation-release-keystore` for account
`jpeng11`. Preserve that key: Android will reject future updates signed by a
different certificate.

The connected phone currently has a debug-signed development build. Installing
this first public build requires uninstalling that debug build, which clears its
local allowlist. Future public releases can update public v0.3.0 in place.

## 10. Known limitations and next work

- Silent install depends on Shizuku staying active. ADB-started Shizuku normally
  needs to be restarted after a reboot.
- A source app that does not preserve verifiable caller identity may require a
  tap despite being allowlisted; this is the safe fallback.
- Container parsing installs every APK member and does not yet select ABI,
  locale, or density splits from large universal archives.
- Only one install request runs at a time.
- All primary owner-facing screens and controls are localized in English and
  Simplified Chinese. Low-level payload, package-manager, or Shizuku failure
  details may remain in English so their original diagnostic text is preserved.
- `QUERY_ALL_PACKAGES` and `REQUEST_INSTALL_PACKAGES` are sensitive under
  Google Play policy. If Play distribution is desired, replace the global
  picker with explicit package entry or a narrower discovery mechanism and
  confirm that the app qualifies for the permitted package-installation use
  case.
- Add instrumented tests for malformed archives, multi-APK sessions, signer
  rotation, cancellation, and process death before a production 1.0 release.

## 11. Primary references

- Android `PackageInstaller`:
  <https://developer.android.com/reference/android/content/pm/PackageInstaller>
- Android activity launch identity:
  <https://developer.android.com/reference/android/app/Activity#getLaunchedFromUid()>
- Android caller identity sharing:
  <https://developer.android.com/reference/android/app/ActivityOptions#setShareIdentityEnabled(boolean)>
- Android package visibility:
  <https://developer.android.com/training/package-visibility>
- Shizuku API repository and integration guide:
  <https://github.com/RikkaApps/Shizuku-API> and
  <https://github.com/RikkaApps/Shizuku-API/blob/master/GUIDE.md>

## 12. Repository hygiene

Ignored local material includes Gradle/IDE state, build outputs, local SDK
configuration, keystores, and `.local-reference/`. No credentials, production
signing keys, legacy APKs, or device dumps belong in Git.
