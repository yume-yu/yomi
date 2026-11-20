#!/usr/bin/env python3
"""
speech.cpp の前に立つ中継サーバー。

- /v1/audio/transcriptions  音声を WAV に変換してから渡す（speech.cpp は WAV しか読まない）
- /v1/audio/speech          読み上げ前にテキストを整形・置換してから渡す
                            response_format=opus なら WAV で受けて Ogg Opus に変換して返す
                            voice ごとの既定値（速度など）を補完する
- /dict/preview             置換結果だけを確認する（音声は生成しない）
- /dict/check               TTS → ASR を往復させて誤読を洗い出す
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

    def reload(self, force: bool = False) -> None:
        if not self.path:
            return
        mtime = self._stat()
        if not force and mtime == self._mtime:
            return
        with self._lock:
            self._mtime = mtime
            regex_rules = []
            literal_map = {}
            errors = []
            try:
                with open(self.path, encoding="utf-8") as f:
                    for lineno, raw in enumerate(f, 1):
                        line = raw.rstrip("\n")
                        if not line.strip() or line.lstrip().startswith("#"):
                            continue
                        if "\t" not in line:
                            errors.append(f"{lineno}行目: タブ区切りではない")
                            continue
                        key, _, value = line.partition("\t")
                        key = key.strip()
                        value = value.strip()
                        if not key:
                            continue
                        if key.startswith("re:"):
                            expr = key[3:]
                            try:
                                regex_rules.append((re.compile(expr), value))
                            except re.error as e:
                                errors.append(f"{lineno}行目: 正規表現が不正 ({e})")
                        else:
                            literal_map[key] = value
            except FileNotFoundError:
                errors.append("辞書ファイルが見つかりません")
            except Exception as e:  # noqa: BLE001
                errors.append(str(e))

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
