# Vibe.now

Vibe.now は、物理ボタンを押している間だけ動画を残し、短い撮影を一日のログとしてつなげられる iPhone / Android 向けネイティブカメラアプリです。

画面は原則として **カメラ** と **ギャラリー** の2つだけです。カメラを開いてすぐ記録でき、撮影途中のセッションはアプリを閉じても保持されます。

> [!NOTE]
> 表示名は `Vibe.now` です。既存テスト版からデータを引き継げるよう、Android の application ID (`app.setlog.capture`) と iOS の内部ターゲット名 (`SetLogCamera`) は v0.1 と同じまま維持しています。

## v0.2 の主な改善

### 録画中のカメラ切り替え

前面・背面切り替えボタンを画面下部中央の押しやすい位置に移動しました。物理録画ボタンを押したまま切り替えると、現在のクリップを安全に確定し、反対側のカメラへ切り替えた後、ボタンが押されたままなら自動的に続きを録画します。

切り替えによって新しい「物理ボタン押下マーカー」は増えません。完成時には複数クリップを時系列順に1本の動画へ結合します。

### TalkBack と競合しない物理操作

Android の「音量＋と音量−を同時に長押し」はアクセシビリティショートカットと競合するため、完全に廃止しました。

初期設定は次のとおりです。

| 操作 | 初期動作 |
|---|---|
| 音量＋を押している間 | 録画 |
| 音量＋を離す | 一時停止（セッションは未確定のまま保持） |
| 音量−を2回押す | 撮影終了・動画作成 |
| 音量−を3回押す | 未確定のままギャラリーへ移動 |

設定画面では、録画キーを音量＋／音量−から選択でき、反対側キーの2回押し・3回押しを「撮影終了」「ギャラリー」「なし」から個別に選べます。

iOS では OS の Capture Controls API が公開する `secondary` / `primary` アクションを同じ考え方で割り当てます。`secondary` は通常音量＋ですが、`primary` は機種・OS設定により音量−、Actionボタン、Camera Control などとして通知される場合があります。

### 過去動画の取り込みと編集

ギャラリー右上の「動画を追加」から、端末内の既存動画を Vibe.now へ取り込めます。取り込んだ動画も通常のログと同様に次を編集できます。

- 名前
- 最大2,000文字のキャプション
- 時刻表示のオン／オフ
- 時刻表示の位置、サイズ、スタイル
- 共有・削除

取り込み時は原本を変更せず、アプリ専用領域へコピーします。

### 時刻オーバーレイ

時刻表示はプレビューを指でドラッグして配置し、ピンチ操作またはスライダーで拡大縮小できます。「上」「中央」「下」のプリセットも用意しています。

スタイルは次の3種類です。

- Clean：背景なし
- Boxed：半透明の黒背景
- Monospaced：等幅フォント

設定は次に作るログの初期値として保存されます。ギャラリーで完成動画の設定を変えて保存すると、元クリップを保持したまま動画を再生成します。

## UI 方針

Android は Material 3 のボタン、ダイアログ、カード、色体系を使用します。iOS は SwiftUI、SF Symbols、Material、標準の bordered / borderedProminent ボタンを使用します。どちらも上部には状態表示だけを残し、主要操作を親指が届きやすい下部へ集約しています。

撮影中は、赤い四隅のインジケーターと明確な「記録中」表示で状態を示します。

## データ安全性

音量キーを離すたびに、その区間を独立したクリップとして確定し、セッション台帳へ原子的に保存します。動画全体を確定する前にアプリを閉じても、保存済みクリップと押下時刻は残ります。

完成動画には以下を記録します。

- セッションID
- 名前・キャプション
- 撮影開始時刻
- 物理録画ボタンを押した実時刻
- 各押下位置の完成動画内オフセット
- 時刻オーバーレイ設定

Android は Media3 の MP4 メタデータへ `app.vibenow.*` キーを埋め込みます。iOS は AVFoundation の共通メタデータへ同等の JSON を格納し、共有時には読みやすい `*-vibenow.json` も添付します。

## 構成

```text
android/   Kotlin + CameraX + Jetpack Compose + Media3
ios/       Swift + SwiftUI + AVFoundation
.github/   Android / iOS の継続ビルド
```

## Android ビルド

必要環境は JDK 17、Android SDK 36、Build Tools 36.0.0 です。

```bash
cd android
./gradlew assembleDebug
```

APK は次へ生成されます。

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions の Android workflow は APK のZIP整合性と APK Signature Scheme の検証も行い、`Vibe.now-Android-debug` という Artifact を生成します。

## iOS ビルド

`ios/SetLogCamera.xcworkspace` を Xcode 27 で開き、自分の Apple Developer Team を選択して実機へビルドします。

署名なしの Simulator / CI 検証は次のコマンドです。

```bash
xcodebuild \
  -workspace ios/SetLogCamera.xcworkspace \
  -scheme SetLogCamera \
  -configuration Debug \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath /tmp/vibenow-derived \
  CODE_SIGNING_ALLOWED=NO \
  build-for-testing
```

## 既知の制約

- 録画中に OS やユーザーがアプリを強制終了した場合、書き込み中だった最後の未確定クリップだけ失われる可能性があります。すでに一時停止済みのクリップは保持されます。
- カメラ切り替え地点には、端末の Camera HAL / AVFoundation の再構成時間に応じた短い映像ギャップが入ることがあります。
- iPhone では公開 Capture Controls API の抽象化により、`primary` が常に音量−だけを意味するとは限りません。
- iPhone 実機向け IPA の作成には Apple Developer の署名情報が必要です。
- 取り込んだ動画の時刻は、Androidでは取り込み日時を起点にし、iOSでは取得できる場合にファイル作成日時を使用します。

## setlog から参考にした考え方

既存の Vlog アプリ setlog が持つ「短い瞬間を時間順に残す」「動画内で時刻を分かりやすく見せる」「操作を複雑にしすぎない」という製品上の考え方を参考にしています。一方で、ソースコード、画像、ロゴ、固有レイアウト、名称は複製していません。Vibe.now は独立したアプリであり、setlog / new chat とは提携・公式関係にありません。

## プライバシー

広告、アカウント、解析SDK、クラウド送信、外部API通信は実装していません。動画・台帳・設定は端末内に保存され、ユーザーが共有操作を選んだときだけ OS の共有シートへ渡されます。
