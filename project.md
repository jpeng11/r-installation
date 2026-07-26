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
`0.7.0` (`versionCode` 7), and its localized launcher name is
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
  the trusted-source manager. It reproduces the legacy unavailable,
  unauthorized, and ready states while keeping GitHub support available.
- `SettingsActivity` recreates the safe, relevant part of the legacy
  preference surface: Shizuku backend status, silent-install and completion
  message switches, opt-in approved-store download monitoring, trusted sources,
  installer-routing diagnostics and a safe resolver test, language,
  appearance, private payload-cache cleanup, version, and support.
- `AppLanguage` uses Android's application-locale API on Android 13 and newer.
  On Android 9–12 it applies the same persisted choice through a localized
  configuration context, so language switching works across the supported
  API range. English, Simplified Chinese, and Traditional Chinese are explicit
  choices.
- `AppAppearance` persists System, Light, or Dark and applies the selected
  resource configuration across API 28–36.
- `TrustedSourcesActivity` lists installed packages and lets the owner approve
  or revoke them. It follows the legacy icon/name/package/switch row design,
  adds the missing full installed-app list, and searches displayed app names
  as well as package IDs.
- `TrustedStore` records a package name plus the SHA-256 digest of its current
  signing certificate, revalidates it before every trusted decision, and
  removes stale entries for uninstalled apps. The package/certificate approvals
  are persistent application data, so they survive process and service restarts
  and same-signed `adb install -r` updates. Uninstalling or clearing R Installer
  Next erases them; uninstalling a source prunes its stale approval.
- `InstallActivity` receives install intents, resolves the caller, prepares
  payloads, evaluates policy, and displays confirmation when required. It can
  authorize or open Shizuku in place and retry preparation/installation
  without losing the incoming request.
- `CallerVerifier` accepts Android-provided launch identity or result-call
  identity as authorization signals. Ownership of every supplied content URI
  and referrer identity are retained only for display and never treated as
  verified caller identity.
- `PayloadPreparer` copies input into app-private cache and safely expands APK
  entries from APKS/APKM/XAPK/ZIP archives.
- `ShizukuBridge` binds the privileged process and transfers read-only file
  descriptors through AIDL.
- `StoreDownloadMonitorService` is an owner-enabled foreground service. It
  snapshots only approved packages, baselines pre-existing files, and requires
  a newly seen direct APK to present the same size and modification time in two
  consecutive five-second snapshots. It keeps a foreground status notification
  visible, pauses when Shizuku is unavailable, and rechecks source trust before
  registration, staging, and installation. Per-path in-flight state prevents
  duplicate registration while hashing; transient automatic failures receive
  at most three silent retries before the next fresh capability is routed to
  review.
- `DetectedApkActivity` presents a review notification flow when the
  silent-install master is off. `DetectedApkStager` copies a registered,
  read-only descriptor into an app-private cache, rechecks its pinned SHA-256,
  and rejects invalid APK metadata or an APK without a verifiable signer.

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

### Shizuku approved-store file monitor

The monitor is a workaround for app stores that bypass Android's resolver and
directly open the system Package Installer. It does not intercept or replace
that explicit intent. After the owner approves a source and separately enables
monitoring, `PrivilegedFileMonitorService` scans:

```text
/storage/emulated/<Binder-caller-user>/Android/data/<approved-source-package>
```

The Shizuku service is deliberately read-only. Source package names and
relative paths are strictly validated, symbolic links are rejected, traversal
and scan counts are bounded, and only direct `.apk`/`.apk.1` candidates are
used. Android user scope is derived from the Binder caller UID. Every registered
candidate is bound to that user, and opening it from another Android user is
rejected.

Registration checks the stable snapshot metadata, hashes the file, and returns
a random, single-use, 15-minute capability plus its SHA-256. Opening the
capability reopens the same regular file without following links, rechecks its
identity, size, and hash, consumes the capability, and returns a read-only file
descriptor. The main process then copies the bytes to its private cache, checks
the hash again, and parses the APK package and signing information before
offering or requesting installation. No source-store path is passed to the
package-manager install service.

The approved package and certificate pin, monitor setting, silent-install
setting, and completion-message setting are persistent. Once Shizuku permission
has been granted, the monitor does not request authorization again for every
file. If the grant is revoked or the server stops, it pauses or fails closed.
Automatic monitoring always uses a foreground service and visible status
notification. Disabling app notifications, the status channel, or the review
channel disables monitoring instead of allowing an undeliverable alert to be
marked handled. If silent install is on, the enabled completion-message setting
emits start, success, and failure toasts; if silent install is off, a
notification opens the explicit review screen.

## 5. Silent-install policy

The policy is intentionally fail-closed:

| Check | Why it is required |
| --- | --- |
| Verified identity | Prevents a caller from claiming another package name. |
| Package on allowlist | Keeps control with the device owner. |
| Pinned certificate matches | Prevents a different signer from taking over an approved package name. |
| Shizuku ready and authorized | Ensures the operation actually runs inside the intended privilege boundary. |
| Silent-install master enabled | Lets the owner disable all automatic installs without clearing the allowlist. |

All checks must pass. If one fails, the user sees the source, payload, identity
method, allowlist state, and an install button. Referrer strings and arbitrary
intent extras are not authorization signals.

### Identity methods and limits

- Android 14+ launch identity (`getLaunchedFromUid` /
  `getLaunchedFromPackage`) is preferred and cross-checked against the package
  manager.
- `getCallingPackage` is accepted when the source started the installer for a
  result.
- If all supplied payloads are `content://` URIs owned by the same exact
  installed package and UID, the provider owner is shown as payload
  attribution. It is deliberately unverified because it does not prove which
  app launched the exported installer or initiated a chooser flow.
- `android-app://` referrers are display-only because callers can influence
  them.
- On older Android releases or through chooser/download-provider handoffs,
  Android may not expose a usable source identity. Those requests correctly
  fall back to confirmation even if the originating store is allowlisted.

For the strongest result, a source app should launch the explicit installer
activity, grant the content URI, and on Android 14+ enable caller identity
sharing with `ActivityOptions.setShareIdentityEnabled(true)`.

### Monitored-folder policy and provenance limit

The monitored-folder path cannot use caller identity because no install intent
is delivered to R Installer Next. Automatic installation through this opt-in
route instead requires all of the following:

1. The owner approved the installed source package and its current signing
   certificate still matches the persistent pin.
2. The owner enabled both folder monitoring and the silent-install master.
3. Notification permission is available and the foreground monitor is active.
4. Shizuku is running with the existing permission grant.
5. A direct APK is new after the baseline and unchanged across two snapshots.
6. The user-scoped capability, SHA-256 rechecks, private staging, APK parsing,
   and signer extraction all succeed.

With silent install off, the same detection creates a review notification
instead. A file's presence inside an approved app's `Android/data` tree is not
cryptographic proof that the approved app wrote it. The device owner must
therefore enable this workaround only for source apps whose private download
tree they trust. The checks above reduce time-of-check/time-of-use and
cross-user risks, but do not turn folder location into verified caller
identity.

## 6. Input and resource safety

Supported direct payloads are `.apk` files. Supported containers are `.apks`,
`.apkm`, `.xapk`, and `.zip`; only `.apk` members are extracted. Safeguards:

- opaque, numeric, `.1`, and extensionless vendor payload names are normalized
  to a private `base.apk`;
- an APK reported as `application/zip` remains a direct APK unless its filename
  explicitly has a supported container suffix;
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
replace the app's Shizuku and trusted-source policy.

Version 0.7.0 additionally requests `POST_NOTIFICATIONS`,
`FOREGROUND_SERVICE`, and `FOREGROUND_SERVICE_SPECIAL_USE` for the explicitly
enabled approved-store monitor. Notification permission is required so its
ongoing foreground status and review alerts cannot be hidden; revoking it
disables the monitor. The special-use declaration is limited to watching
user-approved app-store download folders with Shizuku. The app does **not**
request storage, accessibility, device-admin, root, or all-files access.

`InstallActivity` is exported for install intents and excluded from Recents.
`MainActivity` is exported only as the launcher. `SettingsActivity` is
exported only through Android's standard `APPLICATION_PREFERENCES` entry point;
its controls still require user interaction. `TrustedSourcesActivity` is
internal. Shizuku's required provider is exported with
`INTERACT_ACROSS_USERS_FULL`, following Shizuku's integration contract.

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
| Unit tests | Silent policy requires all five authorization conditions |

The debug fixture exists only to make the identity-sharing and update path
deterministic. It is not part of the production application and should be
removed from a user device after validation.

### Android 14 routing regression

Validated on an AOSP Android 14 (API 34) emulator after a report that 应用宝's
installation flow did not detect R-安装组件:

| Intent shape | v0.3.0 before fix | v0.5.0 |
| --- | --- | --- |
| `INSTALL_PACKAGE`, `content://`, no MIME type | Not offered | `InstallActivity` resolves |
| `VIEW`, standard APK MIME type | Resolves | Resolves |
| `VIEW`, `application/apk.1` | Not offered | `InstallActivity` resolves |
| `VIEW`, `application/1` | Not offered | `InstallActivity` resolves |

The failure occurred before Shizuku or trusted-source policy evaluation:
Android had no matching intent filter through which to deliver these
vendor-style requests. Version 0.5.0 adds the missing no-MIME and vendor-MIME
filters. Requests whose caller identity is not verifiable still open the
confirmation screen instead of silently installing.

The debug fixture then exercised both added routes with a readable
`content://` APK. The no-MIME route offered R-安装组件 in Android's chooser and
the vendor-MIME route opened it directly; both reached the confirmation UI
with the payload name and size populated.

Additional emulator checks verified that numeric/vendor payload names and APKs
reported as ZIP normalize correctly, the confirmation page retains its payload
while Shizuku authorization is requested, stopped Shizuku changes the primary
action to **Open Shizuku**, returning preserves the request, and failed
operations expose explicit non-looping retries. The automated
`test-tools/verify-intent-routing.sh` refuses physical devices by default.

### Version 0.7.0 approved-store monitor result

The monitored-folder path was validated on 2026-07-26 on the same Samsung
SM-S928W, Android 16, user 0 (ADB serial `R3CX20DKM8N`):

- The v0.7.0 debug build (`versionCode` 7) was installed with
  `adb install -r`. The app-data directory inode remained `4280862`; the
  `zh-CN` language, the trusted
  `com.tencent.android.qqdownloader` package/signing-certificate pin,
  `monitor=true`, and `silent install=true` all remained intact.
- The monitor first baselined existing files. The repository's disposable
  `fixture-source/build/outputs/apk/debug/fixture-source-debug.apk`
  (`dev.jpeng.rinstaller.fixture`, version 1) was then copied to
  `/sdcard/Android/data/com.tencent.android.qqdownloader/files/tassistant/apk/codex_rinstaller_fixture_v07_hardened.apk`.
- The new file was unchanged across two five-second snapshots. The service
  registered its user-0 capability and hash, copied it through the read-only
  descriptor into private staging, and requested installation through Shizuku.
  The package-manager result was `Success`.
- `dumpsys package` reported `versionCode=1` and
  `installerPackageName=dev.jpeng.rinstaller`. Logcat confirmed that code
  reached the start- and success-toast paths; no claim is made that those
  transient toasts were captured in a screenshot.
- The temporary APK was deleted and the fixture package was uninstalled.
  Both were verified absent afterward, while the monitor foreground service
  remained active. No real application was installed or updated by this test.

An earlier physical attempt exposed an Android-user scoping bug:
`Environment.getExternalStorageDirectory()` under Shizuku's shell UID threw
`SecurityException: callingPackage does not match UID`. Version 0.7.0 now
derives the Android user from the Binder caller, scopes the root to
`/storage/emulated/<user>/Android/data/<strictly-validated-package>`, binds each
capability to that user, and rejects cross-user opens.

### Samsung Android 16 physical-device result

Validated on a Samsung SM-S928W running Android 16 on 2026-07-26:

- v0.5.0 updated the previously installed development build in place. Its
  app-data inode, Simplified Chinese choice, Shizuku authorization, and pinned
  应用宝 certificate approval were preserved.
- Shizuku reported ready over ADB with UID 2000.
- Android listed `InstallActivity` for standard APK MIME, no-MIME
  `INSTALL_PACKAGE`, and every observed vendor MIME alias.
- A controlled cross-app `application/apk.1` request opened R-安装组件, verified
  the content-provider caller, labeled the unapproved probe **未信任**, loaded
  the APK name and size, and stopped at the confirmation UI without installing.
- Selecting **Always** for `application/apk.1` produced an Android preferred
  activity record with `mAlways=true`.
- A real 应用宝 request used authority `com.tencent.pangu.fileprovider` with
  `ACTION_VIEW` and `application/vnd.android.package-archive`, but Android
  opened Google Package Installer instead of either the original or new
  R-安装组件. On this firmware, selecting **Always** for the standard APK MIME
  continued to produce only `mAlways=false`; Android therefore did not expose
  a durable third-party default for that route.

Android has no public package-installer role and no supported API through which
an ordinary app can silently make itself the preferred APK handler. An app
store that explicitly targets the system Package Installer cannot be
intercepted by manifest filters. Version 0.6.0 therefore replaces the vague
default-installer guidance with an **APK opening behavior** status and safe setup
test: the app sends an implicit standard-MIME request to a private,
non-exported provider that refuses all file access. Android presents its
resolver when available, and `InstallActivity` recognizes a private, one-time,
ten-minute probe token before attempting any payload access. The token is
consumed on first use. No real APK is present, read, or installed. If another
installer opens, it cannot read the probe and the user should press Back. This
remains useful for file managers and stores that use implicit intents; 应用宝
must offer an external-installer path for full integration on firmware where
it forces the system installer.

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

`.github/workflows/android-apk.yml` provides the automated build path:

- pushes and pull requests run unit tests, lint, and the debug APK build;
- non-PR runs decode the protected signing key, build, align, sign, and verify
  the release APK, then retain it as a GitHub Actions artifact; and
- `v*` tag runs create or update the matching GitHub Release assets.

The workflow uses four protected repository secrets:
`ANDROID_SIGNING_KEY_BASE64`, `ANDROID_SIGNING_STORE_PASSWORD`,
`ANDROID_SIGNING_KEY_PASSWORD`, and `ANDROID_SIGNING_KEY_ALIAS`. Secrets are
not available to pull-request signing steps because those steps are skipped.

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
- Approved-store folder monitoring is opt-in, requires a visible foreground
  notification, and currently recognizes only direct `.apk` and `.apk.1`
  files—not APKS/APKM/XAPK/ZIP bundles. Its first scan intentionally baselines
  and ignores files that were already present.
- Folder location is not cryptographic writer identity. The monitor is a
  constrained workaround for trusted stores that explicitly open the system
  installer, not a claim that R Installer Next intercepts Package Installer
  intents.
- The foreground service is not registered as a boot receiver. After a reboot
  (and restarting Shizuku), the owner may need to reopen Settings to resume an
  enabled monitor.
- A source app that does not preserve verifiable caller identity may require a
  tap despite being allowlisted; this is the safe fallback.
- Container parsing installs every APK member and does not yet select ABI,
  locale, or density splits from large universal archives.
- Only one install request runs at a time.
- All primary owner-facing screens and controls are localized in English,
  Simplified Chinese, and Traditional Chinese. Low-level payload,
  package-manager, or Shizuku failure details may remain in English so their
  original diagnostic text is preserved.
- Root, System(Hook), Magisk/SU commands, all-files access, icon hiding,
  uninstall blocking, clearing other apps' caches, and launcher manipulation
  from the legacy app are deliberately not reproduced. They are unnecessary
  for Shizuku installation and would add dangerous or deceptive privileges.
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
