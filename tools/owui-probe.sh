#!/bin/zsh
# OpenWebUI へのリクエストの形を変えて送り比べ、Web 検索やツールが効くかを確かめる。
#
# わかったこと（OpenWebUI 0.13.3）:
#   /api/chat/completions を素のまま呼ぶと、features（Web 検索）も variables（日付）も効かず、
#   ツールの往復も回らない。Web UI と同じく回してもらうには、チャットを作って chat_id・返答の枠の id・
#   session_id を付けて頼む（`chat` ケース）。Yomi の ScriptService はこの形で頼んでいる。
#   参考: https://docs.openwebui.com/reference/server-side-tool-calling
#
# 使い方:
#   export OWUI_URL=https://...        # OpenWebUI の URL（既定は http://192.168.1.7:3000）
#   export OWUI_KEY=sk-...             # API キー（履歴に残さないため環境変数で渡す）
#   export MODEL=<掛け合い用のモデルID>
#   zsh owui-probe.sh info             # モデルの設定・ツール・関数の一覧を見る（すぐ終わる）
#   zsh owui-probe.sh base             # Yomi が今送っているのと同じ形
#   zsh owui-probe.sh search           # + features.web_search
#   zsh owui-probe.sh tools            # + モデルに付いている tool_ids
#   zsh owui-probe.sh filters          # + モデルに付いている filter_ids（thinking の切り替えなど）
#   zsh owui-probe.sh date             # + 今日の日付の変数
#   zsh owui-probe.sh all              # 上の全部
#   zsh owui-probe.sh stream           # ストリーミングで、検索 + 日付だけ（余計なツールは付けない）
#   zsh owui-probe.sh stream-all       # ストリーミングで、全部
#   zsh owui-probe.sh chat             # Web UI と同じく「チャット」として生成させ、終わるまで待って読み出す
#                                      #   THINK=1 を付けると thinking のフィルターも指定する
#                                      #   作ったチャットは OpenWebUI の履歴に残る（DELETE=1 で消す）
#
# 結果は ./owui-probe-<case>.json に保存し、かかった時間と冒頭を表示する。

set -u
BASE=${OWUI_URL:-http://192.168.1.7:3000}
: ${OWUI_KEY:?OWUI_KEY を設定してください}
: ${MODEL:?MODEL を設定してください}
AUTH="Authorization: Bearer $OWUI_KEY"
PROMPT=${PROMPT:-昨日のニュースをまとめて}

case_name=${1:-info}

if [[ $case_name == info ]]; then
  echo "== モデルの設定（toolIds / filterIds / defaultFeatureIds / capabilities / params）"
  curl -s -H "$AUTH" "$BASE/api/v1/models/model?id=$MODEL" \
    | jq '{id, base_model_id, params, meta: (.meta // {} | {toolIds, filterIds, defaultFeatureIds, capabilities})}'
  echo "== ツール"
  curl -s -H "$AUTH" "$BASE/api/v1/tools/" | jq '[.[] | {id, name}]'
  echo "== 関数（フィルターなど）"
  curl -s -H "$AUTH" "$BASE/api/v1/functions/" | jq '[.[] | {id, name, type, is_active, is_global}]'
  exit 0
fi

if [[ $case_name == chat ]]; then
  uid=$(uuidgen | tr A-Z a-z); aid=$(uuidgen | tr A-Z a-z); sid=$(uuidgen | tr A-Z a-z)
  ts=$(date +%s)
  today=$(date +%Y-%m-%d); now=$(date "+%Y-%m-%d %H:%M:%S"); weekday=$(LC_ALL=en_US.UTF-8 date +%A)

  echo "== 1. チャットを作る"
  new=$(jq -n --arg m "$MODEL" --arg p "$PROMPT" --arg u "$uid" --arg a "$aid" --argjson t "$ts" '
    {chat: {title: "Yomi probe", models: [$m],
      history: {currentId: $a, messages: {
        ($u): {id: $u, parentId: null, childrenIds: [$a], role: "user", content: $p, timestamp: $t, models: [$m]},
        ($a): {id: $a, parentId: $u, childrenIds: [], role: "assistant", content: "", model: $m, modelName: $m, timestamp: $t, done: false}
      }},
      messages: [{id: $u, role: "user", content: $p}]}}')
  chat_id=$(curl -s -H "$AUTH" -H "Content-Type: application/json" -d "$new" "$BASE/api/v1/chats/new" | jq -r '.id')
  echo "chat_id=$chat_id  （$BASE/c/$chat_id）"
  [[ -z $chat_id || $chat_id == null ]] && { echo "チャットを作れませんでした"; exit 1; }

  echo "== 2. 生成を頼む"
  body=$(jq -n --arg m "$MODEL" --arg p "$PROMPT" --arg c "$chat_id" --arg u "$uid" --arg a "$aid" --arg s "$sid" \
              --arg d "$today" --arg n "$now" --arg w "$weekday" '
    {model: $m, stream: true, chat_id: $c, id: $a, session_id: $s,
     messages: [{role: "user", content: $p}],
     user_message: {id: $u, parentId: null, role: "user", content: $p},
     features: {web_search: true},
     variables: {"{{CURRENT_DATE}}": $d, "{{CURRENT_DATETIME}}": $n, "{{CURRENT_WEEKDAY}}": $w},
     background_tasks: {title_generation: false, tags_generation: false, follow_up_generation: false}}')
  if [[ ${THINK:-0} == 1 ]]; then body=$(echo "$body" | jq '.filter_ids = ["thinking_sampling_presets"]'); fi
  echo "$body" | jq -c 'del(.messages, .user_message)'
  curl -s -H "$AUTH" -H "Content-Type: application/json" -d "$body" "$BASE/api/chat/completions" | jq -c '.'

  echo "== 3. 終わるまで待つ（15 秒ごと、最大 20 分）"
  start=$(date +%s)
  while true; do
    sleep 15
    tasks=$(curl -s -H "$AUTH" "$BASE/api/tasks/chat/$chat_id" | jq -c '.task_ids // []')
    len=$(curl -s -H "$AUTH" "$BASE/api/v1/chats/$chat_id" | jq --arg a "$aid" '.chat.history.messages[$a].content // "" | length')
    echo "  +$(( $(date +%s) - start ))s  tasks=$tasks  本文 ${len} 文字"
    [[ $tasks == "[]" ]] && break
    (( $(date +%s) - start > 1200 )) && { echo "  20 分たったので打ち切り"; break; }
  done

  echo "== 4. 読み出す"
  out="./owui-probe-chat.json"
  curl -s -H "$AUTH" "$BASE/api/v1/chats/$chat_id" | jq --arg a "$aid" '.chat.history.messages[$a]' > "$out"
  jq '{done, usage, sources: ((.sources // []) | length), keys: keys}' "$out"
  echo "== 出典（先頭 10 件）"
  jq -r '(.sources // [])[] | (.source.name // .source.url // .metadata[0].source // "?")' "$out" 2>/dev/null | head -10
  echo "== 本文の冒頭"
  jq -r '.content' "$out" | head -c 800; echo
  if [[ ${DELETE:-0} == 1 ]]; then
    curl -s -X DELETE -H "$AUTH" "$BASE/api/v1/chats/$chat_id" >/dev/null && echo "== チャットを消しました"
  fi
  exit 0
fi

meta=$(curl -s -H "$AUTH" "$BASE/api/v1/models/model?id=$MODEL" | jq '.meta // {}')
tool_ids=$(echo "$meta" | jq -c '.toolIds // []')
filter_ids=$(echo "$meta" | jq -c '.filterIds // []')
today=$(date +%Y-%m-%d)
now=$(date "+%Y-%m-%d %H:%M:%S")
weekday=$(LC_ALL=en_US.UTF-8 date +%A)

body=$(jq -n --arg model "$MODEL" --arg prompt "$PROMPT" \
  '{model: $model, stream: false, messages: [{role: "user", content: $prompt}]}')

add() { body=$(echo "$body" | jq "$@"); }
case $case_name in
  base) ;;
  search)  add '.features = {web_search: true}' ;;
  tools)   add --argjson t "$tool_ids" '.tool_ids = $t' ;;
  filters) add --argjson f "$filter_ids" '.filter_ids = $f' ;;
  date)    add --arg d "$today" --arg n "$now" --arg w "$weekday" \
             '.variables = {"{{CURRENT_DATE}}": $d, "{{CURRENT_DATETIME}}": $n, "{{CURRENT_WEEKDAY}}": $w}' ;;
  stream)  add --arg d "$today" --arg n "$now" --arg w "$weekday" \
             '.stream = true | .features = {web_search: true}
              | .variables = {"{{CURRENT_DATE}}": $d, "{{CURRENT_DATETIME}}": $n, "{{CURRENT_WEEKDAY}}": $w}' ;;
  stream-all) add --argjson t "$tool_ids" --argjson f "$filter_ids" --arg d "$today" --arg n "$now" --arg w "$weekday" \
             '.stream = true | .features = {web_search: true} | .tool_ids = $t | .filter_ids = $f
              | .variables = {"{{CURRENT_DATE}}": $d, "{{CURRENT_DATETIME}}": $n, "{{CURRENT_WEEKDAY}}": $w}' ;;
  all)     add --argjson t "$tool_ids" --argjson f "$filter_ids" --arg d "$today" --arg n "$now" --arg w "$weekday" \
             '.features = {web_search: true} | .tool_ids = $t | .filter_ids = $f
              | .variables = {"{{CURRENT_DATE}}": $d, "{{CURRENT_DATETIME}}": $n, "{{CURRENT_WEEKDAY}}": $w}' ;;
  *) echo "不明なケース: $case_name"; exit 1 ;;
esac

echo "== 送る内容（$case_name）"
echo "$body" | jq 'del(.messages)'
out="./owui-probe-$case_name.json"
echo "== 送信中…（検索するなら数分かかる）"
curl -s -o "$out" -w "HTTP %{http_code}  最初の応答まで %{time_starttransfer}s  合計 %{time_total}s\n" \
  --max-time 1200 -H "$AUTH" -H "Content-Type: application/json" \
  -d "$body" "$BASE/api/chat/completions"
if [[ $case_name == stream* ]]; then
  echo "== 届いたもの（data: 行の数 / 本文 / ツール呼び出し / それ以外のキー）"
  grep '^data: {' "$out" | sed 's/^data: //' | jq -c '[(.choices[0].delta | keys? // []), (keys - ["choices","id","created","model","object","system_fingerprint"])] | flatten' 2>/dev/null | sort | uniq -c | sort -rn | head -15
  echo "== data: 以外の行（イベントなど）"
  grep -v '^data: ' "$out" | grep -v '^$' | cut -c1-200 | sort | uniq -c | sort -rn | head -10
  echo "== 本文の冒頭"
  grep '^data: {' "$out" | sed 's/^data: //' | jq -j '.choices[0].delta.content // empty' 2>/dev/null | head -c 800; echo
  echo "== 使ったトークン（最後の usage）"
  grep '^data: {' "$out" | sed 's/^data: //' | jq -c 'select(.usage) | .usage | {prompt_tokens, completion_tokens}' 2>/dev/null | tail -1
  exit 0
fi
echo "== 冒頭（$out）"
jq -r '.choices[0].message.content // .' "$out" | head -c 600; echo
echo "== 本文の外に返ってきたキー"
jq -c 'del(.choices) | keys' "$out" 2>/dev/null
