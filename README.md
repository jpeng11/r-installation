# R Installer Next

R Installer Next is a clean-room Android package installer built for modern
Android releases. It uses a Shizuku user service running as Android's `shell`
user to install APKs and split-package bundles without a system confirmation
dialog when—and only when—the request comes from a user-approved, verified
source app.

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
- SHA-256 signing-certificate pinning for every trusted source.
- Verified caller checks using Android caller identity and content-provider
  ownership; referrer strings and package-name extras are never trusted.
- APK, APKS, APKM, XAPK, ZIP, `ACTION_VIEW`, `ACTION_SEND`, and
  `ACTION_SEND_MULTIPLE` input.
- Single-APK and split-package installation through a Shizuku user service.
- Confirmation UI for requests that do not satisfy the silent-install policy.
- Master silent-install and completion-message settings.
- Package visibility is used to display the source-app picker.
- Android’s install-request permission and compatibility intent filters let app
  stores route standard and vendor-style APK requests to R-安装组件; actual
  installation still uses Shizuku and the trusted-source policy.

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

An install starts automatically only if all five conditions are true:

1. The owner has enabled silent installation in Settings.
2. Android verifies the request identity.
3. The package is present in the user's allowlist.
4. Its current signing certificate matches the certificate pinned when it was
   approved.
5. Shizuku is running and permission is granted.

Everything else requires an explicit tap. See [project.md](project.md) for the
architecture, threat model, compatibility notes, test record, and release
procedure.

## License

MIT. Shizuku is a separate dependency governed by its own license.
