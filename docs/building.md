# Building PaperHarbor

## Requirements

- JDK 17 or newer. Android Studio's bundled JBR works.
- Android SDK Platform 34 and Build Tools 34.
- `sdk.dir=...` in `local.properties`, or `ANDROID_HOME` in the environment.

The Gradle wrapper is pinned to 8.9 and verifies the distribution digest. The first build may need
network access for build tools; **that is not a runtime requirement of the APK**.

## Build

```powershell
.\gradlew.bat --offline assembleDebug assembleRelease
```

The release build already sets `debuggable false`. Without a signing configuration it produces an
unsigned APK; with one it produces a signed APK ready to sideload.

## Verify the packaged permissions

```powershell
python -I .\tools\verify-apk.py .\app\build\outputs\apk\release\app-release.apk --build-tools "$env:ANDROID_HOME\build-tools\34.0.0"
```

The script fails unless the packaged permissions are exactly `RECORD_AUDIO`, checks the signature
and prints the SHA-256. This is the check that keeps the offline promise honest: the manifest
removes `INTERNET` and `ACCESS_NETWORK_STATE`, and the packaged APK is inspected, not the source.

## Sign with your own key

Generate the key on your own machine and keep it offline:

```powershell
keytool -genkeypair -keystore D:\PrivateKeys\paperharbor.jks -alias paperharbor -keyalg RSA -keysize 3072 -validity 10000
```

Put the values in `local.properties`, which is git-ignored and never leaves the machine:

```properties
ph.storeFile=D:/PrivateKeys/paperharbor.jks
ph.storePassword=...
ph.keyAlias=paperharbor
ph.keyPassword=...
```

Then `assembleRelease` signs with that key. Alternatively sign an unsigned APK with the helper,
which zipaligns, signs, verifies and prints the digest, and refuses to overwrite an existing file:

```powershell
.\tools\sign-local.ps1 -Keystore D:\PrivateKeys\paperharbor.jks -Alias paperharbor -Unsigned .\app-release-unsigned.apk
```

Neither the key nor the password is stored by the tooling in this repository.

> Every later update must use the same certificate, otherwise Android treats it as a different
> application and refuses to install over the top.

## Translation coverage

Every Chinese UI string is mapped to English in `Str.java`. Before a release, check that the map
still covers the source:

```powershell
python -I .\tools\check_strings.py
```

The script lists any Chinese literal that has no English value; only deliberate non-UI strings
(the "Aa 永" font sample and the "简体中文" language endonym) should remain.

## Tests

Instrumentation (storage, ink history, PDF round-trip, bundled OCR, backup, permissions) runs on a
dedicated device or emulator:

```powershell
.\gradlew.bat --offline connectedDebugAndroidTest
```

UI and layout checks drive the packaged app; pass `--package` for a release build:

```powershell
python -I .\tools\offline-smoke.py --adb "$env:ANDROID_HOME\platform-tools\adb.exe" --serial emulator-5554 --package io.github.kkkzheli.paperharbor
```

```powershell
python -I .\tools\layout-check.py --adb "$env:ANDROID_HOME\platform-tools\adb.exe" --serial emulator-5554 --package io.github.kkkzheli.paperharbor
```

Both scripts create a uniquely named synthetic note, exercise it and delete only that note.
Running them on a personal phone requires `--allow-device` and the owner's consent.
