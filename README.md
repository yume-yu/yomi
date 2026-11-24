# Yomi

自前の音声合成サーバー（speech.cpp の前に立つ speech-proxy）を使って、文章を読み上げる Android アプリです。
読み上げた文章はエピソードとして端末に残り、あとから続きを聞いたり、読みを直したりできます。

サーバーは外部に公開せず、LAN や VPN の中だけで使う前提です。

## できること

- **読み上げ**: 入力した文章を読み上げる。他のアプリからの共有や、クリップボードの内容でも入力できる
- **文字選択メニュー「Yomiあげる」**: 他のアプリで選んだ文字列を、画面を開かずにその場で読み上げる（エピソードには残らない）
- **エピソード**: アプリの画面から読み上げたものは、文章と音声のセットで端末に残る
  - 作るだけ（再生しない）、作成のキャンセル、再生を止めても最後まで作る
  - 続きから再生、シークバー、スクリプトの段落をタップしてそこから再生
  - 一覧とページに、長さとファイルサイズを表示
- **その場で読みを直す**: エピソードの段落を長押しして、speech-proxy の読み辞書にルールを足し、その段落だけ作り直す
- **読み辞書の編集**: speech-proxy の辞書編集画面（`/dict/ui`）をアプリ内で開く
- **台本を作る**: 入力欄の文章を題材に、OpenWebUI のモデルに台本を書かせる（一人語り・掛け合い）
- **掛け合い**: 行の頭に【A】【B】が付いた台本は、A と B を別の声で読み分ける

## 構成

```
Yomi（Android）
 ├─ speech-proxy（proxy.py, FastAPI）── speech.cpp（Irodori-TTS など）
 │    /v1/audio/speech  Markdown 除去・読み辞書の置換 → 合成 → Ogg Opus に変換
 │    /dict/...         読み辞書の取得・保存・プレビュー・編集画面
 └─ OpenWebUI（任意）
      /api/chat/completions  台本の生成
```

- 音声は Ogg Opus（既定 24kbps）で受け取る。モバイル通信の量を抑えるため
- 文章は文末で区切って少しずつ合成し、最初の段落は短くして声が出るまでの待ちを減らす
- 合成した音声は端末にキャッシュする。キャッシュのキーには読み辞書の版を含めるので、辞書を直すと作り直される

## ビルド

必要なもの:

- JDK 17（Gradle 8.9 / AGP 8.7.3 は、より新しい JDK では動かないことがある）
- Android SDK（compileSdk 35）

```sh
export JAVA_HOME=/path/to/jdk-17
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` に `sdk.dir=...` を書くか、`ANDROID_HOME` を設定してください。

単体テスト:

```sh
./gradlew testDebugUnitTest
```

## 設定

アプリの「読み上げ」タブの「設定」から変更できます。

| 項目 | 既定値 | 内容 |
|---|---|---|
| サーバー | `http://192.168.1.6:8090` | speech-proxy の URL |
| モデル | 空欄 | 空欄ならサーバーの既定モデルを使う |
| 声 | `kanna` | 一人で読むときの声 |
| OpenWebUI の URL | `http://192.168.1.7:3000` | 台本の生成に使う |
| API キー | — | OpenWebUI の「設定 → アカウント → API キー」で作る。端末の中だけに保存する |
| 一人語り用・掛け合い用のモデル | — | OpenWebUI 側で作ったカスタムモデル（下記） |
| 掛け合いの声 A・B | `kanna`・`girl` | 【A】【B】の行を読む声 |

### HTTP で使う場合

Android は既定で平文の HTTP を許可しません。HTTP のサーバーを使うときは、
`app/src/main/res/xml/network_security_config.xml` に、そのホストを足してください。
デバッグビルドでは `adb reverse` 用に `127.0.0.1` も許可しています（`app/src/debug/`）。

### VPN をアプリ単位で使っている場合

Yomi を VPN の対象に加えてください。辞書の TSV ダウンロードは、Android の DownloadManager を使わず
Yomi 自身が取得します（DownloadManager はシステムのプロセスが通信するので、アプリ単位の VPN を通らないため）。

## 台本の生成（OpenWebUI）

Yomi は題材（入力欄の文章）だけを送り、台本の書き方は OpenWebUI 側のカスタムモデルのシステムプロンプトに任せます。
モデルの作り方とプロンプトの例は [docs/openwebui-script-models.md](docs/openwebui-script-models.md) にあります。

掛け合い用のモデルは、各発言の行の頭に【A】か【B】を付けて出力してください。Yomi はこの印で話し手を見分けます。

## speech-proxy（proxy.py）

speech.cpp の前に立つ中継サーバーです。OpenWebUI や LiveKit など、Yomi 以外のクライアントも使う前提なので、
既存のエンドポイントの挙動は変えず、機能は追加する形で足していきます。

主な環境変数:

| 変数 | 既定値 | 内容 |
|---|---|---|
| `SPEECH_UPSTREAM` | `http://127.0.0.1:8089` | speech.cpp の URL |
| `SPEECH_TTS_DICT` | — | 読み辞書（TSV）のパス |
| `SPEECH_DICT_UI` | — | 辞書編集画面の HTML。空なら proxy.py の隣の `dict_ui.html` |
| `SPEECH_STRIP_MARKDOWN` | `1` | 読み上げ前に Markdown を除去する |
| `SPEECH_OPUS_BITRATE` | `24k` | Ogg Opus に変換するときのビットレート |
| `SPEECH_VOICE_DEFAULTS` | — | voice ごとの既定値（JSON）。例: `{"girl": {"duration_scale": 0.85}}` |
| `SPEECH_KEYWORDS` | — | 音声認識のキーワードのファイル |
| `SPEECH_TIMEOUT` | `300` | 上流へのタイムアウト（秒） |

`ffmpeg` が必要です。辞書編集画面の `dict_ui.html` は、このリポジトリには入っていません。

辞書の保存（`POST /dict`）に `expected_loaded_at`（`GET /dict` の `status.loaded_at`）を添えると、
読んだ後に別の保存が入っていた場合は書き込まずに 409 を返します。添えなければ従来どおり保存します。

## ソースの見取り図

| ファイル | 内容 |
|---|---|
| `MainActivity.kt` | 画面（読み上げタブ、エピソードタブ、設定） |
| `EpisodeViews.kt` | エピソードの一覧・カード・ページ、シークバー |
| `Episode.kt` / `EpisodeStore.kt` | エピソードのデータと保存（`filesDir/episodes/<id>/`） |
| `GenerateService.kt` | エピソードの音声を作る前面サービス（再生とは独立） |
| `SpeakService.kt` | 再生（Media3 の MediaSessionService）。通知から操作できる |
| `ProcessTextActivity.kt` | 文字選択メニュー「Yomiあげる」 |
| `TtsClient.kt` | speech-proxy から音声を取得し、キャッシュする |
| `TextSplitter.kt` | 文章を読み上げ用の段落に分ける |
| `DictClient.kt` / `FixReading.kt` / `DictActivity.kt` | 読み辞書の API、その場で読みを直す、辞書の編集画面 |
| `OpenWebUiClient.kt` / `Script.kt` / `ScriptDialog.kt` | 台本の生成と、掛け合いの台本の読み取り |

やりたいことと検討のメモは [ROADMAP.md](ROADMAP.md) にあります。
