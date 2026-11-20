# Yomi 野望リスト

## 前提（現状の構成）
- サーバー: speech.cpp（Irodori-TTS、:8089）の前に speech-proxy（FastAPI、192.168.1.6:8090）
  - /v1/audio/speech: Markdown 除去と読み辞書による置換をしてから speech.cpp に渡す
  - response_format=opus なら WAV で受けて ffmpeg で Ogg Opus（既定 24kbps）に変換して返す
  - VOICE_DEFAULTS で voice ごとの既定値を補完
  - 合成は _tts_lock で直列化
- 外部には公開しない。LAN と WireGuard 経由でのみ使う（HTTPS 化や公開ドメインは使わない）
- アプリ: Kotlin + Compose、Media3（ExoPlayer + MediaSessionService）、OkHttp
  - 文末で分割し（最初のチャンクは 40 字以内）、チャンクごとに opus で取得、端末にキャッシュしてプレイリストに積む
  - 指定した文字列の読み上げは動作確認済み
- voice: kanna、girl
- モバイル通信は容量内でやりくりしているので、通信量は少ないほど良い

## 野望（優先順）

### 1. 文字選択メニューから直接読み上げ
- ACTION_PROCESS_TEXT の透明 Activity → SpeakService の実装は入っているが、実機で未検証
- 要確認: アプリが背面にある状態から再生開始したときに、
  ForegroundServiceStartNotAllowedException が出ないか（Android 12 以降）
  今は再生が始まるまで透明 Activity を残し、タッチは下の画面に素通しする方式

> **状況（2026-11-20、Pixel 9a / API 37）**
> - adb から PROCESS_TEXT の Intent を投げる経路では、再生開始まで約 1.5 秒、例外なし。
>   長文（448 字、6 チャンク）も最後まで再生。通知のミュージックコントロールで停止・再開・前へも動作。
> - 他アプリ（Misskey クライアント）で文字を選んで「読み上げ」を押す経路も確認済み。
>   再生開始まで約 1.2 秒。前面サービスの開始は `uidState: TOP` で許可され、例外なし。
> - 対処済み: 透明 Activity のタイムアウトを 20 秒から 60 秒に延ばし（他の利用者のリクエストで
>   `_tts_lock` が埋まっていても、合成を待てるように）、取得に失敗したらすぐ閉じるようにした。
>   60 秒を超えて待たされた場合は、Activity が閉じたあとに前面サービスを開始することになるので、例外が出る可能性は残る。

### 2. 通信量をさらに削る
- Opus のビットレートを詰める（16k〜24k で聞き比べ）
- キャッシュのキーが「モデル・voice・文章」だけなので、
  proxy の読み辞書や VOICE_DEFAULTS を変えても古い音声が返る
  → proxy が設定のバージョン（辞書の mtime など）を返し、キーに含める

> **メモ**
> - **proxy は OpenWebUI と LiveKit も使っている。** 既存エンドポイント（特に /v1/audio/speech）の
>   挙動は変えず、新しいエンドポイントやオプトインのパラメータを足すだけにする。
> - キャッシュの判定は取得の前に行うので、バージョンは事前に知る必要がある。
>   読み上げ開始時に 1 回だけ小さな GET（既存の `/dict/status` の `loaded_at` を拡張するなど）で取るのが簡単。
> - 2026-11-20 時点で、モデルは空欄（サーバーの既定）で送っている。
>   サーバー側のモデルを差し替えたときも古い音声が返るので、モデル ID もバージョンに含めたい。

### 3. 分割の改善
- 改行で区切るため、Markdown のコードブロックがチャンクをまたぐと proxy の除去が効かない
  → 分割前に端末側でコードブロックを処理するか、分割自体を proxy に寄せる

### 4. 最終形: Android の TTS エンジンになる
- TextToSpeechService を実装し、システムの「選択して読み上げ」や
  他アプリの読み上げ機能を、すべて speech.cpp の声で喋らせる
- 実装量が大きいので、1〜3 が片付いてから

> **メモ**
> - TextToSpeechService は PCM をコールバックで返す仕組みなので、Opus を端末でデコードする（MediaCodec）か、
>   この経路だけ WAV で受けるかを選ぶことになる。
