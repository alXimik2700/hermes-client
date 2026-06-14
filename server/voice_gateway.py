"""
Hermes Voice Gateway v2 - WebSocket voice streaming
Port 5002 | FastAPI + WebSocket
AudioRecord (16kHz PCM) -> WS -> Whisper STT -> Hermes AI -> Piper TTS -> WS -> AudioTrack (22kHz PCM)
"""
import os, sys, json, time, subprocess, tempfile, uuid, shutil, sqlite3, threading, asyncio
from pathlib import Path
from collections import deque
import requests
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, Query, Request
from fastapi.responses import JSONResponse
from slowapi import Limiter, _rate_limit_exceeded_handler
from slowapi.util import get_remote_address
from slowapi.errors import RateLimitExceeded
import uvicorn

BASE_DIR = Path(__file__).resolve().parent
CONFIG_PATH = BASE_DIR / "config.json"

# Load config - prefer environment variables over config.json
CFG = json.loads(CONFIG_PATH.read_text(encoding="utf-8")) if CONFIG_PATH.exists() else {}

WHISPER_CLI = os.environ.get("WHISPER_CLI", "/path/to/tool")
WHISPER_MODEL = os.environ.get("WHISPER_MODEL", "/path/to/tool")
PIPER_VOICE = os.environ.get("PIPER_VOICE", "your-piper-voice")
PIPER_MODEL_DIR = os.environ.get("PIPER_MODEL_DIR", "/path/to/tool")

# Generic AI backend (OpenAI-compatible API)
AI_URL = os.environ.get("AI_URL", CFG.get("ai_url", "http://127.0.0.1:11434"))
AI_KEY = os.environ.get("AI_KEY", CFG.get("ai_key", ""))
AI_MODEL = os.environ.get("AI_MODEL", CFG.get("ai_model", "llama3"))
AI_SYSTEM_PROMPT = os.environ.get("AI_SYSTEM_PROMPT", CFG.get("ai_system_prompt", "You are a helpful assistant."))

# Legacy fallback
HERMES_URL = os.environ.get("HERMES_API_URL", CFG.get("hermes_api_url", AI_URL))
HERMES_KEY = os.environ.get("HERMES_API_KEY", CFG.get("hermes_api_key", AI_KEY))
API_TOKEN = os.environ.get("API_TOKEN", CFG.get("api_token", ""))
GATEWAY_PORT = 5002
DB_PATH = BASE_DIR / "messages.db"

app = FastAPI()

# Rate limiting
limiter = Limiter(key_func=get_remote_address)
app.state.limiter = limiter
app.add_exception_handler(RateLimitExceeded, _rate_limit_exceeded_handler)

# --- Internal broadcast endpoint (called by server.py) ---

INTERNAL_SECRET = os.environ.get("INTERNAL_SECRET", "")

@app.post("/_internal/broadcast")
@limiter.limit("100 per minute")
async def internal_broadcast(data: dict, request: Request):
    import hmac as _hmac
    secret = request.headers.get("X-Internal-Secret", "")
    if not INTERNAL_SECRET or not _hmac.compare_digest(secret, INTERNAL_SECRET):
        return JSONResponse({"error": "unauthorized"}, status_code=401)
    broadcast_message(data)
    return JSONResponse({"ok": True})

# --- DB helpers ---

def db_save(sender, text, client_uuid=None):
    with sqlite3.connect(str(DB_PATH)) as db:
        if client_uuid:
            existing = db.execute("SELECT id FROM messages WHERE client_uuid=?", (client_uuid,)).fetchone()
            if existing:
                return existing[0]
        db.execute("INSERT INTO messages (sender,text,client_uuid) VALUES (?,?,?)", (sender, text, client_uuid))
        db.commit()
        return db.execute("SELECT last_insert_rowid()").fetchone()[0]

def db_get_since(since_id, limit=50):
    with sqlite3.connect(str(DB_PATH)) as db:
        db.row_factory = sqlite3.Row
        rows = db.execute("SELECT id,sender,text,sender_name,target,created_at FROM messages WHERE id > ? ORDER BY id ASC LIMIT ?", (since_id, limit)).fetchall()
        return [{"id": r["id"], "sender": r["sender"], "text": r["text"], "sender_name": r["sender_name"], "target": r["target"], "time": r["created_at"]} for r in rows]

# --- Message streaming ---

connected_clients: dict[str, WebSocket] = {}  # token -> websocket
message_queue: deque = deque(maxlen=500)
broadcast_tasks: list = []

def broadcast_message(msg: dict):
    message_queue.append(msg)
    import asyncio
    loop = None
    try:
        loop = asyncio.get_running_loop()
    except RuntimeError:
        pass
    if loop and loop.is_running():
        for token, ws in list(connected_clients.items()):
            try:
                loop.create_task(ws.send_text(json.dumps(msg)))
            except Exception:
                pass
    else:
        # Fallback: queue for next event loop iteration
        for token, ws in list(connected_clients.items()):
            try:
                import threading
                threading.Thread(target=lambda: asyncio.run(ws.send_text(json.dumps(msg)))).start()
            except Exception:
                pass

# --- Whisper STT ---

FFMPEG_CLI = shutil.which("ffmpeg") or ""

def _to_wav(src_path):
    ext = os.path.splitext(src_path)[1].lower()
    if ext == '.wav':
        return src_path
    if not FFMPEG_CLI:
        return src_path
    wav_path = src_path + ".whisper.wav"
    try:
        subprocess.run(
            [FFMPEG_CLI, '-y', '-i', str(src_path), '-ar', '16000', '-ac', '1', '-c:a', 'pcm_s16le', wav_path],
            capture_output=True, text=True, timeout=30, check=True)
        return wav_path
    except Exception:
        return None

def whisper_stt(audio_data: bytes) -> str | None:
    tmp = Path(tempfile.gettempdir()) / f"ws_voice_{uuid.uuid4().hex[:8]}.raw"
    wav_path = Path(tempfile.gettempdir()) / f"ws_voice_{uuid.uuid4().hex[:8]}.wav"
    try:
        tmp.write_bytes(audio_data)
        # Convert raw PCM to proper WAV with header
        converted = _to_wav(str(tmp))
        if not converted:
            # Fallback: write WAV manually from raw PCM (16kHz, 16-bit, mono)
            import struct, io
            sample_rate = 16000
            bits_per_sample = 16
            num_channels = 1
            data_size = len(audio_data)
            with open(str(wav_path), 'wb') as f:
                # RIFF header
                f.write(b'RIFF')
                f.write(struct.pack('<I', 36 + data_size))
                f.write(b'WAVE')
                # fmt chunk
                f.write(b'fmt ')
                f.write(struct.pack('<I', 16))
                f.write(struct.pack('<H', 1))  # PCM
                f.write(struct.pack('<H', num_channels))
                f.write(struct.pack('<I', sample_rate))
                f.write(struct.pack('<I', sample_rate * num_channels * bits_per_sample // 8))
                f.write(struct.pack('<H', num_channels * bits_per_sample // 8))
                f.write(struct.pack('<H', bits_per_sample))
                # data chunk
                f.write(b'data')
                f.write(struct.pack('<I', data_size))
                f.write(audio_data)
            converted = str(wav_path)
        result = subprocess.run(
            [WHISPER_CLI, '-m', WHISPER_MODEL, '-f', converted,
             '-l', 'ru', '--no-timestamps', '-t', '4'],
            capture_output=True, text=True, timeout=60)
        lines = [l.strip() for l in result.stdout.split('\n') if l.strip()]
        for line in reversed(lines):
            if line and not line.startswith(('whisper_', 'main:', 'system_info', 'ggml_')):
                if len(line) > 2:
                    print(f"[ws_stt] {line[:100]}", flush=True)
                    return line
        print(f"[ws_stt] whisper returned no text, stderr={result.stderr[-200:]}", flush=True)
        return None
    except Exception as e:
        print(f"[ws_stt_error] {e}", flush=True)
        return None
    finally:
        for f in [tmp, wav_path, Path(str(tmp) + ".whisper.wav")]:
            try:
                f.unlink(missing_ok=True)
            except Exception:
                pass

# --- TTS: Piper ---

def piper_tts(text: str) -> bytes | None:
    try:
        from piper.voice import PiperVoice
        model_path = f"{PIPER_MODEL_DIR}/{PIPER_VOICE}.onnx"
        config_path = f"{PIPER_MODEL_DIR}/{PIPER_VOICE}.onnx.json"
        if not os.path.exists(model_path):
            from piper.download_voices import download_voice
            os.makedirs(PIPER_MODEL_DIR, exist_ok=True)
            download_voice(PIPER_VOICE, Path(PIPER_MODEL_DIR))
        pv = PiperVoice.load(model_path, config_path=config_path)
        chunks = list(pv.synthesize(text[:500]))
        return b"".join(chunk.audio_int16_bytes for chunk in chunks)
    except Exception as e:
        print(f"[ws_tts_error] {e}", flush=True)
        return None

# --- AI: Generic OpenAI-compatible chat completions ---

def call_ai(msg: str) -> str | None:
    """Call any OpenAI-compatible API (Hermes, Ollama, OpenAI, DeepSeek, etc.)"""
    try:
        headers = {"Content-Type": "application/json"}
        if AI_KEY:
            headers["Authorization"] = f"Bearer {AI_KEY}"
        r = requests.post(f"{AI_URL}/v1/chat/completions",
            headers=headers,
            json={"model": AI_MODEL,
                  "messages": [
                      {"role": "system", "content": AI_SYSTEM_PROMPT},
                      {"role": "user", "content": msg}
                  ],
                  "max_tokens": 4096, "temperature": 0.7},
            timeout=120)
        if r.status_code == 200:
            choices = r.json().get("choices", [])
            if choices:
                return choices[0]["message"]["content"].strip()
        print(f"[ai] HTTP {r.status_code}", flush=True)
        return None
    except Exception as e:
        print(f"[ai_error] {e}", flush=True)
        return None

# Backward compat
def call_hermes(msg: str) -> str | None:
    return call_ai(msg)

# --- Voice pipeline ---

async def process_voice(websocket: WebSocket, audio_buffer: bytearray):
    t0 = time.time()

    text = whisper_stt(bytes(audio_buffer))
    if not text:
        await websocket.send_text(json.dumps({"status": "error", "error": "Voice not recognized"}))
        return

    print(f"[ws] STT: {text[:80]}", flush=True)
    await websocket.send_text(json.dumps({"status": "transcribed", "text": text}))

    # Save transcription to DB and broadcast
    transcribed_id = db_save("user", f"[Transcribed] {text}")
    broadcast_message({"messages": [{"id": transcribed_id, "sender": "user", "text": f"[Transcribed] {text}", "time": ""}]})

    reply = call_hermes(text) or "[Hermes is busy]"
    print(f"[ws] AI: {reply[:80]}", flush=True)
    await websocket.send_text(json.dumps({"status": "ai_reply", "text": reply}))

    # Save AI reply to DB and broadcast
    ai_id = db_save("ai", reply)
    broadcast_message({"messages": [{"id": ai_id, "sender": "ai", "text": reply, "time": ""}]})

    # Send streaming tokens (word by word for UI effect)
    words = reply.split()
    accumulated = ""
    for i, word in enumerate(words):
        accumulated += (" " if i > 0 else "") + word
        broadcast_message({"stream_token": {"message_id": ai_id, "token": word + " "}})
        time.sleep(0.03)
    broadcast_message({"stream_complete": {"message_id": ai_id, "text": reply}})

    pcm = piper_tts(reply)
    if pcm:
        CHUNK = 4096
        for i in range(0, len(pcm), CHUNK):
            await websocket.send_bytes(pcm[i:i + CHUNK])
        print(f"[ws] TTS: {len(pcm)} bytes streamed", flush=True)

        # Save TTS file reference to DB
        tts_id = db_save("ai", f"[VoiceReply: tts_{ai_id}.wav]")
        broadcast_message({"messages": [{"id": tts_id, "sender": "ai", "text": f"[VoiceReply: tts_{ai_id}.wav]", "time": ""}]})

    elapsed = int((time.time() - t0) * 1000)
    await websocket.send_text(json.dumps({"status": "done", "elapsed_ms": elapsed}))

# --- WebSocket handlers ---

async def _authenticate_ws(websocket: WebSocket, token: str = Query(None)):
    """Authenticate WebSocket: token from query param OR first message."""
    import hmac as _hmac
    # Try query param first (backward compat)
    if token and _hmac.compare_digest(token, API_TOKEN):
        await websocket.accept()
        return True
    # No query token — accept and wait for first message with auth
    await websocket.accept()
    try:
        first_msg = await asyncio.wait_for(websocket.receive_text(), timeout=5.0)
        data = json.loads(first_msg)
        msg_token = data.get("token", "")
        if msg_token and _hmac.compare_digest(msg_token, API_TOKEN):
            return True
        await websocket.close(code=4001, reason="unauthorized")
        return False
    except Exception:
        await websocket.close(code=4001, reason="unauthorized")
        return False

@app.websocket("/api/messages/stream")
@app.websocket("/messages/stream")
@app.websocket("/stream")
@app.websocket("/")
async def message_stream(websocket: WebSocket, token: str = Query(None)):
    if not await _authenticate_ws(websocket, token):
        return
    connected_clients[token] = websocket
    print(f"[ws] Message client connected", flush=True)

    try:
        # Send initial since ID and catch up missed messages
        first_msg = await websocket.receive_text()
        try:
            data = json.loads(first_msg)
            since_id = data.get("since", 0)
        except Exception:
            since_id = 0

        # Send missed messages
        missed = db_get_since(since_id)
        if missed:
            await websocket.send_text(json.dumps({"messages": missed}))
            print(f"[ws] Sent {len(missed)} missed messages since {since_id}", flush=True)

        # Keep connection alive and handle pings
        while True:
            msg = await websocket.receive_text()
            try:
                data = json.loads(msg)
                if data.get("ping"):
                    await websocket.send_text(json.dumps({"pong": True}))
            except json.JSONDecodeError:
                pass
    except WebSocketDisconnect:
        pass
    except Exception as e:
        print(f"[ws_msg_error] {e}", flush=True)
    finally:
        connected_clients.pop(token, None)
        print("[ws] Message client disconnected", flush=True)

@app.websocket("/api/voice-stream")
@app.websocket("/api/voice-stream/")
async def voice_stream(websocket: WebSocket, token: str = Query(None)):
    if not await _authenticate_ws(websocket, token):
        return

    print("[ws] Voice client connected", flush=True)

    audio_buffer = bytearray()

    try:
        while True:
            msg = await websocket.receive()

            if msg.get("type") == "websocket.receive":
                if "text" in msg:
                    try:
                        data = json.loads(msg["text"])
                        action = data.get("action", "")
                    except json.JSONDecodeError:
                        action = ""

                    if action == "end":
                        if len(audio_buffer) >= 16000:
                            await process_voice(websocket, audio_buffer)
                        else:
                            await websocket.send_text(json.dumps({"status": "error", "error": "Audio too short"}))
                        audio_buffer.clear()
                        continue

                if "bytes" in msg:
                    audio_buffer.extend(msg["bytes"])
                    if len(audio_buffer) >= 320000:
                        await process_voice(websocket, audio_buffer)
                        audio_buffer.clear()

            elif msg.get("type") == "websocket.disconnect":
                break

    except WebSocketDisconnect:
        print("[ws] Voice client disconnected", flush=True)
    except Exception as e:
        print(f"[ws_error] {e}", flush=True)
    finally:
        if len(audio_buffer) >= 16000:
            print(f"[ws] Processing {len(audio_buffer)} bytes leftover audio", flush=True)
            try:
                await process_voice(websocket, audio_buffer)
            except Exception:
                pass
        audio_buffer.clear()
        print("[ws] Voice session ended", flush=True)

if __name__ == "__main__":
    print(f"[config] ai={AI_URL} model={AI_MODEL}", flush=True)
    print(f"[config] whisper={WHISPER_CLI}", flush=True)
    print(f"[config] piper={PIPER_VOICE}", flush=True)
    print(f"[config] listen=0.0.0.0:{GATEWAY_PORT}", flush=True)
    uvicorn.run(app, host="0.0.0.0", port=GATEWAY_PORT)
