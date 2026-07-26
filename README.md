# R Installer Next

R Installer Next is a clean-room Android package installer built for modern
Android releases. It uses a Shizuku user service running as Android's `shell`
user to install APKs and split-package bundles without a system confirmation
dialog when—and only when—the request comes from a user-approved, verified
source app. Version 0.7.0 also has an optional foreground monitor for direct
APK files newly written under an approved store's app-specific download tree.

The project does not contain code or assets from the older `R-安装组件`
application. Its behavior and public Android manifest were examined only to
define compatibility goals.

## Download

Download the latest signed APK from
[GitHub Releases](https://github.com/jpeng11/r-installation/releases/latest).
The APK and its SHA-256 checksum are published together.

The latest published release may lag the current development version; check the
release notes before testing app-store routing. The public APK uses the
permanent release certificate. Android cannot update a debug-signed development
build with it, so uninstall any development build before installing the first
public release.

## Features

- Android 16 target (`compileSdk`/`targetSdk` 36), Android 9 minimum.
- Searchable trusted-source list; any currently installed app can be added.
- Familiar R-安装组件 state cards, preference-style Settings screen, app icons,
  and switches.
- Complete English, Simplified Chinese, and Traditional Chinese UI with an
  in-app language selector.
- System, light, and dark appearance choices.
- In-place Shizuku authorization/recovery and retry without losing the APK
  selected by an app store.
- Trusted-source search matches the displayed app name (including Chinese
  names such as 应用宝) as well as the package ID.
- Persistent SHA-256 signing-certificate pinning for every trusted source.
  Approval survives app/service restarts and same-signed in-place updates, and
  is revalidated before each trusted operation.
- Verified caller checks use Android launch/result identity. Content-provider
  ownership is displayed only as payload attribution; referrer strings,
  provider ownership, and package-name extras never authorize a silent install.
- APK, APKS, APKM, XAPK, ZIP, `ACTION_VIEW`, `ACTION_SEND`, and
  `ACTION_SEND_MULTIPLE` input.
- Single-APK and split-package installation through a Shizuku user service.
- Confirmation UI for requests that do not satisfy the silent-install policy.
- Master silent-install and completion-message settings.
- Opt-in approved-store folder monitoring for new direct APK files, including
  a mandatory foreground status notification.
- Two unchanged snapshots are required before a monitored file is registered.
Shizuku opens it read-only through a short-lived capability, its SHA-256 is
rechecked, and it is copied into private staging before package inspection.
Each path has only one registration/install operation in flight. Transient
automatic failures are retried up to three times, then a fresh capability is
used for a review notification instead of silently dropping the file.
- When silent installation is enabled, monitored installs use localized
  start/success/failure toasts; otherwise a notification opens a review screen.
- Installer-routing status and a safe resolver test that contains no APK and
  cannot install anything.
- Package visibility is used to display the source-app picker.
- Android’s install-request permission and compatibility intent filters let app
  stores route standard and vendor-style APK requests to R-安装组件; actual
  installation still uses Shizuku and the trusted-source policy.

Android does not expose a public “default APK installer” role. Stores that use
implicit APK intents can be directed through Android's resolver, but a store
that explicitly targets the system Package Installer cannot be intercepted by
another app. The Settings → **APK opening behavior** test reports and explains
this boundary.

### Approved-store folder monitor

This is the workaround for stores such as 应用宝 on firmware where the store
opens the system Package Installer directly. Approve the store under **Manage
trusted source apps**, then explicitly enable **Detect approved app downloads**
in Settings. Android notification permission and a running, already-authorized
Shizuku server are required. The app does not request Shizuku authorization
again for each detected APK; if that existing grant or the server becomes
unavailable, monitoring pauses or fails closed.

While enabled, a foreground notification remains visible. Disabling app
notifications or either monitor channel disables the monitor rather than
silently losing an alert. The first scan
records a baseline and does not act on existing files. A new `.apk` or `.apk.1`
must have the same size and modification time in two consecutive five-second
snapshots before it can proceed. With the silent-install master enabled, the
file installs automatically; with it disabled, the user receives a review
notification and must tap Install. The completion-message switch controls the
start, success, and failure toasts.

The monitor does **not** intercept, replace, or observe an explicit
`PackageInstaller` intent. It reads only the current Android user's
`/storage/emulated/<user>/Android/data/<approved-package>` tree through a
read-only Shizuku service. Folder location alone is not cryptographic proof of
which process wrote a file, so use this feature only with source apps you
trust. Certificate revalidation, two-snapshot stability, user-scoped
capabilities, hash verification, APK signature parsing, and private staging
reduce—but cannot erase—that provenance limitation.

## Build

Requirements: JDK 17, Android SDK Platform 36, and an Android 16-compatible
Shizuku installation.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions runs the same tests, lint, and debug build for pushes and pull
requests. Pushes to `main` also produce a signed APK in the workflow artifacts,
and a `v*` tag attaches the signed APK and checksum to its GitHub Release.
Release signing uses protected repository secrets; the private key is never
stored in the repository or workflow artifacts.

Open R Installer Next, grant its Shizuku authorization, then open **Manage
trusted source apps** and select the app store or file manager that should be
allowed to request silent installs.

`fixture-source` is a debug-only integration-test app. It shares its own APK
through a content provider and opts into Android's caller-identity sharing so
the trusted path can be tested on a device:

```sh
./gradlew :fixture-source:assembleDebug
adb install -r -t fixture-source/build/outputs/apk/debug/fixture-source-debug.apk
```

## Security boundary

An intent-delivered install starts automatically only if all five conditions
are true:

1. The owner has enabled silent installation in Settings.
2. Android verifies the request identity.
3. The package is present in the user's allowlist.
4. Its current signing certificate matches the certificate pinned when it was
   approved.
5. Shizuku is running and permission is granted.

Everything else requires an explicit tap. See [project.md](project.md) for the
architecture, threat model, compatibility notes, test record, and release
procedure.

The opt-in folder-monitor route has no incoming caller identity to verify.
Instead it requires a still-approved source package with the same pinned
certificate, the monitor and silent-install switches, notification permission,
Shizuku readiness, a stable new file, and successful capability/hash/private
staging checks. Revoking the source or changing its signer stops automatic use.

## License

MIT. Shizuku is a separate dependency governed by its own license.
