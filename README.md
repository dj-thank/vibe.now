# SetLog Camera

物理ボタンを押している間だけ必要な瞬間を記録し、一連のクリップをあとで1本の動画として確定する Android / iPhone 向けネイティブカメラアプリです。

## 画面

基本画面は2つだけです。

- **Camera** — 起動直後に表示。物理ボタン操作を中心にした全画面カメラ。
- **Gallery** — 未確定セッションと完成動画を表示。再開、再生、名前変更、キャプション、共有、削除、押下時刻の確認ができます。

## 操作

### Android

- 音量＋を押している間だけ録画
- 音量＋を離すと現在のクリップを閉じて一時停止
- 音量＋と音量−を同時に2秒長押しするとバイブレーション後に撮影終了・動画確定
- 音量−を短時間に3回押すと、未確定のままギャラリーへ移動

### iPhone

- Capture Controls の secondary action（音量＋）を押している間だけ録画
- 離すと一時停止
- primary action の長押し / 3回押しと、画面上の終了操作をフォールバックとして使用

Appleの公開API上、音量−・Actionボタン・Camera Control は primary action として扱われ、Androidと同じ意味で2つの音量ボタンの同時押しを全機種で保証できないためです。

## セッション方式

音量＋を1回押している間を1クリップとして安全に閉じ、セッション台帳へ保存します。動画を確定する前にアプリを終了しても、すでに閉じたクリップは残り、次回起動時に同じセッションへ続きを追加できます。

確定時にクリップを時系列順で1本のMP4へ連結します。各押下には実時刻と完成動画内のタイムライン位置が記録されます。

## Repository layout

```text
android/        Kotlin + CameraX + Jetpack Compose + Media3
ios/            Swift + SwiftUI + AVFoundation
.github/        Android / iOS build verification
VALIDATION.md   ビルド済み環境と実機で残る検証項目
```

## Build IDs

- Android application ID: `app.setlog.capture`
- iOS bundle ID: `app.setlog.camera`
- Version: `0.1.0` / build 1
- Android minSdk: 29
- iOS deployment target: 26.0

## Privacy

広告SDK、解析SDK、アカウント、クラウド送信、アプリ独自のネットワーク通信はありません。録画と台帳はローカルへ保存し、ユーザーが共有操作をした場合だけOSの共有シートへ渡します。

## Status

Android APKは `assembleDebug`、APK ZIP整合性、APK Signature Scheme v2 を検証済みです。iOSはXcode 27 beta 4 / iPhoneSimulator 27.0 SDKで `build-for-testing` に成功済みです。詳細は `VALIDATION.md` を参照してください。
