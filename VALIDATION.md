# SetLog Camera v0.1.0 — Validation

検証日: 2026-08-29

## Android

- Ubuntu GitHub Actions runner
- JDK 17
- Gradle 9.5.0
- Android Gradle Plugin 9.3.2
- Kotlin 2.4.10
- compileSdk / targetSdk 36
- minSdk 29
- CameraX 1.7.0-alpha03
- Media3 1.11.0
- Jetpack Compose BOM 2026.05.00
- `assembleDebug`: PASS
- APK ZIP CRC (`unzip -t`): PASS
- APK Signature Scheme v2: PASS
- Debug APK SHA-256: `1f9c636b4bdf1ed103c14a0abce13e880c581a9070828cc8b485d15a43c92fc7`

## iOS

- macOS hosted runner
- Xcode 27 beta 4 (`27A5228h`)
- iPhoneSimulator 27.0 SDK
- deployment target 26.0
- Swift 5.10 language mode
- `xcodebuild ... build-for-testing`: PASS
- SetLogCamera + SetLogCameraTests: PASS
- arm64 + x86_64 simulator build: PASS
- `Info.plist`, `PrivacyInfo.xcprivacy`, `project.pbxproj` validation: PASS

## Source / privacy checks

- Android camera, microphone and vibration permissions: verified
- iOS camera and microphone usage descriptions: verified
- iOS privacy manifest: verified
- No Firebase, Crashlytics, Sentry, Amplitude, Mixpanel, Retrofit, OkHttp or URLSession based telemetry/network feature in the app source
- Japanese and English localization included
- Android/iOS session ledger and recovery path included
- MP4 marker metadata included

## Real-device checks still required

コンパイル検証では代替できないため、次は実機で確認が必要です。

- Pixel / Galaxy等の物理音量キー
- OEMごとのCamera HAL、録画品質、音声同期
- Androidの＋/−同時押し端末差
- iPhoneのVolume Up、primary capture action、Action Button、Camera Control
- iPhone公開API上での物理音量キー同時押し挙動
- 着信、通知、画面ロック、権限変更、ストレージ不足
- 録画中のOS強制終了
- 30分以上の長時間利用と発熱
- 多数クリップの結合性能
- App Store / Google Play本番署名と配布
