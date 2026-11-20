#!/usr/bin/env python3
"""
speech.cpp の前に立つ中継サーバー。

- /v1/audio/transcriptions  音声を WAV に変換してから渡す（speech.cpp は WAV しか読めない）
- /v1/audio/speech          読み上げ前にテキストを整形・置換してから渡す
                            response_format=opus なら WAV で受けて Ogg Opus に変換して返す
                            voice ごとの既定値（速度など）を補完する
- /dict/preview             置換結果だけを確認する（音声は生成しない）
- /dict/check               TTS → ASR を往復させて誤読を洗い出す
- /dict (GET/POST)          辞書の全件取得・保存（書式検証・原子書き込み付き）
- /dict/export              辞書の TSV をそのままダウンロード
- /dict/ui                  辞書の編集・プレビュー用 Web UI
"""

import asyncio
import json
import os
import re
import shutil
import subprocess
import tempfile
import threading
import time

import httpx
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import JSONResponse, Response

UPSTREAM = os.environ.get("SPEECH_UPSTREAM", "http://127.0.0.1:8089")
ASR_KEYWORDS_FILE = os.environ.get("SPEECH_KEYWORDS", "")
TTS_DICT_FILE = os.environ.get("SPEECH_TTS_DICT", "")
STRIP_MARKDOWN = os.environ.get("SPEECH_STRIP_MARKDOWN", "1") != "0"
TIMEOUT = float(os.environ.get("SPEECH_TIMEOUT", "300"))
OPUS_BITRATE = os.environ.get("SPEECH_OPUS_BITRATE", "24k")

# 辞書 UI の場所。空ならディレクトリ探索（dict_ui.html が proxy.py の隣にある場合）。
DICT_UI_FILE = os.environ.get("SPEECH_DICT_UI", "")

# voice ごとの既定値。リクエストに同じキーがあればそちらを優先する。
# 環境変数 SPEECH_VOICE_DEFAULTS に JSON で渡しても上書きできる。
#   例: SPEECH_VOICE_DEFAULTS='{"girl": {"duration_scale": 0.85}}'
VOICE_DEFAULTS: dict[str, dict] = {
    # "girl": {"duration_scale": 0.85},
    # "kanna": {},
}
if os.environ.get("SPEECH_VOICE_DEFAULTS"):
    VOICE_DEFAULTS.update(json.loads(os.environ["SPEECH_VOICE_DEFAULTS"]))

app = FastAPI()
_tts_lock = asyncio.Lock()

# ---------------------------------------------------------------------------
# 読み辞書
# ---------------------------------------------------------------------------


class Dictionary:
    """TSV の読み辞書。mtime を見て自動で読み直す。"""

    def __init__(self, path: str):
        self.path = path
        self._lock = threading.Lock()
        self._mtime = 0.0
        self._regex_rules: list[tuple[re.Pattern, str]] = []
        self._literal_pattern: re.Pattern | None = None
        self._literal_map: dict[str, str] = {}
        self._error: str | None = None
        self.reload(force=True)

    def _stat(self) -> float:
        try:
            return os.path.getmtime(self.path)
        except OSError:
            return 0.0

    def _parse(self, text: str) -> tuple[list[tuple[str, str]], list[tuple[str, str]], list[str]]:
        """TSV 文字列を (regex_rules, literal_rules, errors) に分解する。"""
        regex_rules: list[tuple[str, str]] = []
        literal: dict[str, str] = {}
        errors: list[str] = []
        seen_regex: dict[str, int] = {}
        seen_literal: dict[str, int] = {}
        for lineno, line in enumerate(text.split("\n"), 1):
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            if "\t" not in line:
                errors.append(f"{lineno}行目: タブ区切りではない")
                continue
            key, _, value = line.partition("\t")
            key = key.strip()
            value = value.strip()
            if not key:
                errors.append(f"{lineno}行目: キーが空")
                continue
            if key.startswith("re:"):
                expr = key[3:]
                try:
                    re.compile(expr)
                except re.error as e:
                    errors.append(f"{lineno}行目: 正規表現が不正 ({e})")
                    continue
                if expr in seen_regex:
                    errors.append(f"{lineno}行目: 正規表現 {expr!r} が重複")
                    continue
                seen_regex[expr] = lineno
                regex_rules.append((expr, value))
            else:
                if key in seen_literal:
                    errors.append(f"{lineno}行目: キー {key!r} が重複")
                    continue
                seen_literal[key] = lineno
                literal[key] = value
        return regex_rules, list(literal.items()), errors

    def reload(self, force: bool = False) -> None:
        if not self.path:
            return
        mtime = self._stat()
        if not force and mtime == self._mtime:
            return
        with self._lock:
            self._mtime = mtime
            regex_rules: list[tuple[re.Pattern, str]] = []
            literal_map: dict[str, str] = {}
            errors: list[str] = []
            try:
                with open(self.path, encoding="utf-8") as f:
                    raw = f.read()
            except FileNotFoundError:
                raw = None
            except Exception as e:  # noqa: BLE001
                raw = None
            if raw is None:
                errors.append("辞書ファイルが見つかりません")
            else:
                exprs, literals, errors = self._parse(raw)
                for expr, value in exprs:
                    regex_rules.append((re.compile(expr), value))
                literal_map = dict(literals)

            self._regex_rules = regex_rules
            self._literal_map = literal_map
            # 長いキーから先に当てる。1回の走査で置換するので、
            # 置換後の文字列が別のキーに再マッチすることはない。
            if literal_map:
                keys = sorted(literal_map, key=len, reverse=True)
                self._literal_pattern = re.compile(
                    "|".join(re.escape(k) for k in keys)
                )
            else:
                self._literal_pattern = None
            self._error = "; ".join(errors) if errors else None

    def apply(self, text: str) -> str:
        self.reload()
        with self._lock:
            regex_rules = list(self._regex_rules)
            pattern = self._literal_pattern
            mapping = dict(self._literal_map)
        for pat, repl in regex_rules:
            text = pat.sub(repl, text)
        if pattern is not None:
            text = pattern.sub(lambda m: mapping[m.group(0)], text)
        return text

    def status(self) -> dict:
        self.reload()
        with self._lock:
            return {
                "path": self.path or None,
                "literal_rules": len(self._literal_map),
                "regex_rules": len(self._regex_rules),
                "loaded_at": self._mtime,
                "error": self._error,
            }

    # ---- 全件取得・保存（dict UI 用） -------------------------------------

    def rules(self) -> list[dict]:
        """現在の内容を {kind, pattern, replacement, line} のリストで返す。"""
        self.reload()
        if not self.path or not os.path.exists(self.path):
            return []
        with open(self.path, encoding="utf-8") as f:
            raw = f.read()
        exprs, literals, _ = self._parse(raw)
        out: list[dict] = []
        for expr, value in exprs:
            out.append({"kind": "regex", "pattern": expr, "replacement": value})
        for key, value in literals:
            out.append({"kind": "literal", "pattern": key, "replacement": value})
        return out

    def save(self, rules: list[dict]) -> dict:
        """検証して TSV として原子書き込みする。エラーがあれば書き込まない。"""
        if not self.path:
            return {"error": "辞書ファイルのパスが未設定 (SPEECH_TTS_DICT)"}
        text = render_rules(rules)
        _, _, errors = self._parse(text)
        if errors:
            return {"saved": False, "errors": errors}
        self._write_atomic(text)
        self.reload(force=True)
        with self._lock:
            return {
                "saved": True,
                "errors": [],
                "literal_rules": len(self._literal_map),
                "regex_rules": len(self._regex_rules),
                "loaded_at": self._mtime,
            }

    def _write_atomic(self, text: str) -> None:
        d = os.path.dirname(self.path) or "."
        fd, tmp = tempfile.mkstemp(prefix=".tts-dict-", dir=d)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                f.write(text)
            os.replace(tmp, self.path)
        except Exception:
            try:
                os.unlink(tmp)
            except OSError:
                pass
            raise

    def preview_rules(self, rules: list[dict], text: str) -> dict:
        """保存前のルールで置換結果を計算する（ファイルは触らない）。"""
        exprs, literals, errors = self._parse(render_rules(rules))
        for expr, value in exprs:
            text = re.compile(expr).sub(value, text)
        literal_map = dict(literals)
        if literal_map:
            keys = sorted(literal_map, key=len, reverse=True)
            pat = re.compile("|".join(re.escape(k) for k in keys))
            text = pat.sub(lambda m: literal_map[m.group(0)], text)
        return {"after": text, "errors": errors}


def render_rules(rules: list[dict]) -> str:
    """{kind, pattern, replacement} のリストを TSV 文字列に戻す。"""
    lines = []
    for rule in rules:
        kind = rule.get("kind", "literal")
        pattern = rule.get("pattern", "")
        value = rule.get("replacement", "")
        if not pattern:
            continue
        key = f"re:{pattern}" if kind == "regex" else pattern
        lines.append(f"{key}\t{value}")
    return "\n".join(lines) + ("\n" if lines else "")


def rules_from_tsv(text: str) -> list[dict]:
    """TSV 文字列を rules リストに変換する（POST /dict の content 受け用）。"""
    rules = []
    for line in text.split("\n"):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if "\t" not in line:
            continue
        key, _, value = line.partition("\t")
        key = key.strip()
        value = value.strip()
        if not key:
            continue
        if key.startswith("re:"):
            rules.append({"kind": "regex", "pattern": key[3:], "replacement": value})
        else:
            rules.append({"kind": "literal", "pattern": key, "replacement": value})
    return rules


tts_dict = Dictionary(TTS_DICT_FILE)


# ---------------------------------------------------------------------------
# Markdown の除去
# ---------------------------------------------------------------------------

_CODE_FENCE = re.compile(r"```[\s\S]*?```")
_INLINE_CODE = re.compile(r"`([^`\n]+)`")
_IMAGE = re.compile(r"!\[([^\]]*)\]\([^)]*\)")
_LINK = re.compile(r"\[([^\]]+)\]\([^)]*\)")
_HEADING = re.compile(r"^\s{0,3}#{1,6}\s+", re.M)
_QUOTE = re.compile(r"^\s{0,3}>\s?", re.M)
_LIST = re.compile(r"^\s*[-*+]\s+", re.M)
_ORDERED = re.compile(r"^\s*\d+[.)]\s+", re.M)
_HR = re.compile(r"^\s*([-*_])\s*(\1\s*){2,}$", re.M)
_BOLD = re.compile(r"\*\*([^*]+)\*\*")
_ITALIC = re.compile(r"(?<!\*)\*([^*\n]+)\*(?!\*)")
_TABLE_SEP = re.compile(r"^\s*\|?[\s:|-]+\|[\s:|-]*$", re.M)
_SPACES = re.compile(r"[ \t]{2,}")
_BLANKS = re.compile(r"\n{3,}")


def strip_markdown(text: str) -> str:
    text = _CODE_FENCE.sub("、コードは省略します。", text)
    text = _IMAGE.sub(r"\1", text)
    text = _LINK.sub(r"\1", text)
    text = _INLINE_CODE.sub(r"\1", text)
    text = _HR.sub("", text)
    text = _TABLE_SEP.sub("", text)
    text = _HEADING.sub("", text)
    text = _QUOTE.sub("", text)
    text = _LIST.sub("", text)
    text = _ORDERED.sub("", text)
    text = _BOLD.sub(r"\1", text)
    text = _ITALIC.sub(r"\1", text)
    text = text.replace("|", "、")
    text = _SPACES.sub(" ", text)
    text = _BLANKS.sub("\n\n", text)
    return text.strip()


def prepare_text(text: str) -> str:
    if STRIP_MARKDOWN:
        text = strip_markdown(text)
    return tts_dict.apply(text)


# ---------------------------------------------------------------------------
# ASR（既存）
# ---------------------------------------------------------------------------


def load_keywords() -> str:
    if not ASR_KEYWORDS_FILE or not os.path.exists(ASR_KEYWORDS_FILE):
        return ""
    lines = []
    with open(ASR_KEYWORDS_FILE, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#"):
                lines.append(line)
    return "\n".join(lines)


def to_wav(raw: bytes, workdir: str) -> str:
    src = os.path.join(workdir, "input.bin")
    dst = os.path.join(workdir, "audio.wav")
    with open(src, "wb") as f:
        f.write(raw)
    proc = subprocess.run(
        ["ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
         "-i", src, "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", dst],
        capture_output=True,
    )
    if proc.returncode != 0 or not os.path.exists(dst):
        raise HTTPException(
            status_code=400,
            detail=f"ffmpeg failed: {proc.stderr.decode('utf-8', 'ignore')[:300]}",
        )
    return dst


async def transcribe_bytes(raw: bytes, model: str = None) -> dict:
    with tempfile.TemporaryDirectory() as tmp:
        dst = to_wav(raw, tmp)
        data = {"response_format": "json"}
        if model:
            data["model"] = model
        keywords = load_keywords()
        if keywords:
            data["keywords"] = keywords
        with open(dst, "rb") as f:
            files = {"file": ("audio.wav", f, "audio/wav")}
            async with httpx.AsyncClient(timeout=TIMEOUT) as client:
                r = await client.post(
                    f"{UPSTREAM}/v1/audio/transcriptions", files=files, data=data
                )
    try:
        return r.json()
    except Exception:  # noqa: BLE001
        return {"error": r.text[:500]}


@app.post("/v1/audio/transcriptions")
async def transcriptions(
    file: UploadFile = File(...),
    model: str = Form(None),
    language: str = Form(None),
    prompt: str = Form(None),
    response_format: str = Form("json"),
):
    raw = await file.read()
    if not raw:
        raise HTTPException(status_code=400, detail="empty file")
    result = await transcribe_bytes(raw, model)
    return JSONResponse(result)


# ---------------------------------------------------------------------------
# TTS
# ---------------------------------------------------------------------------


async def synthesize(body: dict) -> httpx.Response:
    async with httpx.AsyncClient(timeout=TIMEOUT) as client:
        return await client.post(f"{UPSTREAM}/v1/audio/speech", json=body)


def apply_voice_defaults(body: dict) -> dict:
    payload = dict(body)
    for key, value in VOICE_DEFAULTS.get(body.get("voice", ""), {}).items():
        payload.setdefault(key, value)
    return payload


async def wav_to_opus(wav: bytes) -> bytes:
    """WAV を Ogg Opus に変換する。サンプルレートは WAV ヘッダから拾う。"""
    proc = await asyncio.create_subprocess_exec(
        "ffmpeg", "-hide_banner", "-loglevel", "error",
        "-f", "wav", "-i", "pipe:0",
        "-ac", "1", "-ar", "48000",
        "-c:a", "libopus", "-b:a", OPUS_BITRATE,
        "-application", "voip",
        "-f", "ogg", "pipe:1",
        stdin=asyncio.subprocess.PIPE,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    out, err = await proc.communicate(wav)
    if proc.returncode != 0 or not out:
        raise HTTPException(
            status_code=500,
            detail=f"opus encode failed: {err.decode('utf-8', 'ignore')[:300]}",
        )
    return out


@app.post("/v1/audio/speech")
async def speech(body: dict):
    original = body.get("input", "")
    if not isinstance(original, str):
        raise HTTPException(status_code=400, detail="input must be a string")

    payload = apply_voice_defaults(body)
    payload["input"] = prepare_text(original)

    want_opus = body.get("response_format") == "opus"
    if want_opus:
        payload["response_format"] = "wav"

    async with _tts_lock:
        r = await synthesize(payload)
    if r.status_code >= 400:
        try:
            return JSONResponse(r.json(), status_code=r.status_code)
        except Exception:  # noqa: BLE001
            return JSONResponse(
                {"error": {"message": r.text[:500]}}, status_code=r.status_code
            )

    # 変換はロックの外で行い、次の合成を待たせない
    if want_opus:
        return Response(await wav_to_opus(r.content), media_type="audio/ogg")

    return Response(
        r.content,
        media_type=r.headers.get("content-type", "audio/wav"),
    )


# ---------------------------------------------------------------------------
# 辞書の確認用
# ---------------------------------------------------------------------------


@app.post("/dict/preview")
async def dict_preview(body: dict):
    text = body.get("text", "")
    stripped = strip_markdown(text) if STRIP_MARKDOWN else text
    return {
        "before": text,
        "stripped": stripped,
        "after": tts_dict.apply(stripped),
    }


@app.get("/dict/preview")
async def dict_preview_get(text: str = ""):
    """UI からクエリで呼べる版。POST と同じ内容。"""
    stripped = strip_markdown(text) if STRIP_MARKDOWN else text
    return {
        "before": text,
        "stripped": stripped,
        "after": tts_dict.apply(stripped),
    }


@app.post("/dict/check")
async def dict_check(body: dict):
    """TTS → ASR を往復させて、読み間違いを洗い出す。"""
    text = body.get("text", "")
    if not text:
        raise HTTPException(status_code=400, detail="text is required")

    spoken = prepare_text(text)
    tts_body = {"input": spoken, "response_format": "wav"}
    for key in ("model", "voice", "seed", "duration_scale", "steps", "speed"):
        if key in body:
            tts_body[key] = body[key]
    tts_body = apply_voice_defaults(tts_body)

    r = await synthesize(tts_body)
    if r.status_code >= 400:
        return JSONResponse(
            {"error": {"message": r.text[:500]}}, status_code=r.status_code
        )

    heard = await transcribe_bytes(r.content, body.get("asr_model"))
    heard_text = heard.get("text", "") if isinstance(heard, dict) else ""

    return {
        "input": text,
        "spoken": spoken,
        "heard": heard_text,
        "match": _normalize(spoken) == _normalize(heard_text),
        "audio_bytes": len(r.content),
    }


_PUNCT = re.compile(r"[、。，．,.\s!?！？「」『』()（）]")


def _normalize(text: str) -> str:
    return _PUNCT.sub("", text)


# ---------------------------------------------------------------------------
# 辞書の CRUD（dict UI 用）
# ---------------------------------------------------------------------------


@app.get("/dict")
async def dict_get():
    """現在の内容を rules リスト（+ status）で返す。"""
    return {
        "rules": tts_dict.rules(),
        "status": tts_dict.status(),
    }


@app.post("/dict")
async def dict_post(body: dict):
    """辞書全体を保存する。

    - rules: [{kind: "regex"|"literal", pattern, replacement}, ...] を渡す
    - content: TSV 文字列をそのまま渡す（rules が優先）
    検証（タブ区切り・正規表現コンパイル・重複）に失敗したら書き込まず
    errors を返す。成功時は mtime 再読み込みで即反映される。
    """
    if "rules" in body:
        rules = body["rules"]
        if not isinstance(rules, list):
            raise HTTPException(status_code=400, detail="rules must be a list")
        result = tts_dict.save(rules)
    elif "content" in body:
        result = tts_dict.save(rules_from_tsv(body["content"]))
    else:
        raise HTTPException(status_code=400, detail="rules or content is required")
    if not result.get("saved"):
        return JSONResponse(result, status_code=400)
    return result


@app.post("/dict/validate")
async def dict_validate(body: dict):
    """保存せずに書式チェックだけする。"""
    if "rules" in body:
        rules = body["rules"]
        if not isinstance(rules, list):
            raise HTTPException(status_code=400, detail="rules must be a list")
        text = render_rules(rules)
    elif "content" in body:
        text = body["content"]
    else:
        raise HTTPException(status_code=400, detail="rules or content is required")
    _, _, errors = tts_dict._parse(text)
    return {"valid": not errors, "errors": errors}


@app.post("/dict/preview-rules")
async def dict_preview_rules(body: dict):
    """保存前のルールで置換結果を計算する（ファイルは触らない）。"""
    if "rules" in body:
        rules = body["rules"]
        if not isinstance(rules, list):
            raise HTTPException(status_code=400, detail="rules must be a list")
        text = body.get("text", "")
        stripped = strip_markdown(text) if STRIP_MARKDOWN else text
        result = tts_dict.preview_rules(rules, stripped)
        return {"before": text, "stripped": stripped, **result}
    elif "content" in body:
        text = body.get("text", "")
        stripped = strip_markdown(text) if STRIP_MARKDOWN else text
        result = tts_dict.preview_rules(rules_from_tsv(body["content"]), stripped)
        return {"before": text, "stripped": stripped, **result}
    else:
        raise HTTPException(status_code=400, detail="rules or content is required")


@app.get("/dict/export")
async def dict_export():
    """辞書の TSV をそのまま返す（バックアップ用）。"""
    if not tts_dict.path or not os.path.exists(tts_dict.path):
        raise HTTPException(status_code=404, detail="dict file not found")
    with open(tts_dict.path, encoding="utf-8") as f:
        raw = f.read()
    return Response(
        raw,
        media_type="text/tab-separated-values; charset=utf-8",
        headers={
            "Content-Disposition": 'attachment; filename="tts-dict.tsv"'
        },
    )


def _find_ui_file() -> str | None:
    candidates = [
        DICT_UI_FILE,
        os.path.join(os.path.dirname(os.path.abspath(__file__)), "dict_ui.html"),
    ]
    for c in candidates:
        if c and os.path.exists(c):
            return c
    return None


@app.get("/dict/ui")
async def dict_ui():
    """辞書の編集・プレビュー用 Web UI を返す。"""
    path = _find_ui_file()
    if path is None:
        raise HTTPException(
            status_code=404,
            detail="dict_ui.html が見つかりません (SPEECH_DICT_UI も未設定)",
        )
    with open(path, encoding="utf-8") as f:
        return Response(f.read(), media_type="text/html; charset=utf-8")


@app.get("/dict/status")
async def dict_status():
    return {
        "dictionary": tts_dict.status(),
        "strip_markdown": STRIP_MARKDOWN,
        "asr_keywords": bool(load_keywords()),
        "upstream": UPSTREAM,
    }


# ---------------------------------------------------------------------------
# その他
# ---------------------------------------------------------------------------


@app.get("/v1/models")
async def models():
    async with httpx.AsyncClient(timeout=TIMEOUT) as client:
        r = await client.get(f"{UPSTREAM}/v1/models")
        return JSONResponse(r.json(), status_code=r.status_code)


@app.get("/health")
async def health():
    return {"ok": True, "ffmpeg": shutil.which("ffmpeg") is not None}
