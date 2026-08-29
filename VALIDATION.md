# Vibe.now v0.2.0 — 検証記録

検証日: 2026-08-29

## 対象変更

- 録画キーを押したままの前面／背面カメラ切り替え
- 主要操作を画面下部へ移動
- Android / iOS の標準コンポーネントへUIを再構成
- Android の両音量キー長押しを完全廃止
- 録画キー、2回押し、3回押しの割り当て設定
- 端末内の既存動画の取り込み
- 名前・キャプション・時刻表示の編集
- 時刻オーバーレイのオン／オフ、ドラッグ、ピンチ、サイズ、位置プリセット、スタイル
- 表示名を Vibe.now へ変更
- v0.1 の保存領域とアプリ識別子を維持したデータ移行

## ローカル静的検証

- 全 Swift ファイルを Swift 6.2.1 の `swiftc -parse` で解析
- iOS の `Info.plist` と `PrivacyInfo.xcprivacy` を解析
- Android の全 resource XML を解析
- Android 日英文字列キーの完全一致を確認
- Android の `R.string.*` 参照に欠落がないことを確認
- iOS 日英 Localizable.strings のキー完全一致を確認
- Swift の `String(localized:)` 参照に欠落がないことを確認
- 両音量キー同時長押し／旧 chord detector が実装に残っていないことを検索
- Gradle 9.5.0 binary distribution の公式 SHA-256 を wrapper 設定に固定

## 継続ビルド

### Android

`.github/workflows/android.yml` は以下を実行します。

1. JDK 17 と Android SDK 36 を構成
2. `assembleDebug`
3. APK の全ZIPエントリー検査
4. `apksigner verify`
5. デバッグ APK を Artifact として保存

### iOS

`.github/workflows/ios.yml` は以下を実行します。

1. plist / Privacy Manifest / pbxproj 検証
2. Xcode 27 で `build-for-testing`
3. アプリ本体と XCTest ターゲットのコンパイル

## 操作上の不変条件

- 両音量キーの同時長押しをアプリ操作として要求しない
- 1つの物理入力だけを「押している間の録画」に使用する
- 反対側の入力は独立した複数回押しとして解決する
- 3回押しが成立したとき、保留中の2回押し操作は実行しない
- カメラ切り替えによる継続クリップは、新しい物理押下マーカーを生成しない
- クリップ確定前に完成動画の台帳へ追加しない
- インポート元ファイルを直接変更しない
- 完成動画の再生成中に旧完成ファイルを失わないよう、一時ファイルとバックアップを使用する
- オーバーレイの正規化座標と倍率を安全範囲へ制限する

## 実機で追加確認が必要な項目

- Android OEM ごとの音量キーイベント、Camera HAL、TalkBack設定との組み合わせ
- iPhone 機種ごとの primary / secondary Capture Controls の割り当て
- 録画中カメラ切り替え地点の映像・音声ギャップ
- 30分以上の長時間セッション、発熱、着信、ストレージ不足
- 各種コーデック／コンテナのインポートと再生成
- 時刻オーバーレイの縦横動画・回転動画での位置精度
- Apple Developer 署名を使用した実機 Archive / IPA

CI の実行結果とコミットは、変更反映後の GitHub Actions に記録されます。
