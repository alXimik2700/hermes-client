"""
Hermes Messenger v3 - Full Voice Pipeline
STT: whisper.cpp (BLAS) | AI: Hermes session | TTS: Piper + DirectML GPU
"""
import os, sys, json, time, sqlite3, secrets, threading, subprocess, io, shutil
from datetime import datetime, timezone
from pathlib import Path
from dotenv import load_dotenv
import requests
from flask import Flask, request, jsonify, g
from flask_limiter import Limiter
from flask_limiter.util import get_remote_address

BASE_DIR = Path(__file__).resolve().parent
load_dotenv(BASE_DIR / ".env")
DB_PATH = BASE_DIR / "messages.db"
CONFIG_PATH = BASE_DIR / "config.json"
UPLOAD_FOLDER = BASE_DIR / "uploads"
os.makedirs(UPLOAD_FOLDER, exist_ok=True)

# Load config - prefer environment variables over config.json
CFG = json.loads(CONFIG_PATH.read_text(encoding="utf-8")) if CONFIG_PATH.exists() else {}

# Voice pipeline paths
WHISPER_CLI = os.environ.get("WHISPER_CLI", "/path/to/tool")
WHISPER_MODEL = os.environ.get("WHISPER_MODEL", "/path/to/tool")
PIPER_VOICE = os.environ.get("PIPER_VOICE", "your-piper-voice")
PIPER_MODEL_DIR = os.environ.get("PIPER_MODEL_DIR", "/path/to/tool")

# Generic AI backend (OpenAI-compatible API)
# Supports: Hermes, Ollama, LM Studio, vLLM, OpenAI, DeepSeek, etc.
AI_URL = os.environ.get("AI_URL", CFG.get("ai_url", "http://127.0.0.1:11434"))
AI_KEY = os.environ.get("AI_KEY", CFG.get("ai_key", ""))
AI_MODEL = os.environ.get("AI_MODEL", CFG.get("ai_model", "llama3"))
AI_SYSTEM_PROMPT = os.environ.get("AI_SYSTEM_PROMPT", CFG.get("ai_system_prompt", "You are a helpful assistant."))

# Legacy Hermes config (fallback to AI_URL if not set)
HERMES_URL = os.environ.get("HERMES_API_URL", CFG.get("hermes_api_url", AI_URL))
HERMES_KEY = os.environ.get("HERMES_API_KEY", CFG.get("hermes_api_key", AI_KEY))
HERMES_SID = os.environ.get("HERMES_SESSION_ID", CFG.get("hermes_session_id", ""))

API_TOKEN = os.environ.get("API_TOKEN", CFG.get("api_token", ""))
LISTEN_HOST = os.environ.get("LISTEN_HOST", CFG.get("listen_host", "0.0.0.0"))
LISTEN_PORT = int(os.environ.get("LISTEN_PORT", CFG.get("listen_port", 5000)))

_agent_state = {"status": "online", "model": "", "tokens": 0, "last_active": 0, "lock": threading.Lock()}

app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = 50 * 1024 * 1024

# Rate limiting
limiter = Limiter(
    key_func=get_remote_address,
    app=app,
    default_limits=["200 per day", "50 per hour"],
    storage_uri="memory://"
)

sys.path.insert(0, "/path/to/tool")

# --- DB ---
def get_db():
    if "db" not in g:
        g.db = sqlite3.connect(str(DB_PATH))
        g.db.row_factory = sqlite3.Row
        g.db.execute("PRAGMA journal_mode=WAL")
    return g.db

@app.teardown_appcontext
def close_db(exc):
    db = g.pop("db", None)
    if db: db.close()

def init_db():
    db = sqlite3.connect(str(DB_PATH))
    db.execute("""CREATE TABLE IF NOT EXISTS messages (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        sender TEXT NOT NULL CHECK(sender IN ('user','ai')),
        text TEXT NOT NULL,
        client_uuid TEXT,
        sender_name TEXT DEFAULT 'user',
        target TEXT DEFAULT 'hermes',
        created_at TEXT NOT NULL DEFAULT (datetime('now')))""")
    # Migration: add columns if missing
    for col, default in [("client_uuid", None), ("sender_name", "'user'"), ("target", "'hermes'")]:
        try:
            db.execute(f"SELECT {col} FROM messages LIMIT 1")
        except sqlite3.OperationalError:
            db.execute(f"ALTER TABLE messages ADD COLUMN {col} TEXT DEFAULT {default}")
    try:
        db.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_client_uuid ON messages(client_uuid) WHERE client_uuid IS NOT NULL")
    except sqlite3.OperationalError:
        pass
    db.execute("CREATE INDEX IF NOT EXISTS idx_msg_time ON messages(created_at)")
    db.commit(); db.close()

def db_save(sender, text, client_uuid=None, sender_name="user", target="hermes"):
    with sqlite3.connect(str(DB_PATH)) as db:
        if client_uuid and client_uuid.strip():
            existing = db.execute("SELECT id FROM messages WHERE client_uuid=?", (client_uuid,)).fetchone()
            if existing:
                return existing[0]
        else:
            client_uuid = None
        db.execute("INSERT INTO messages (sender,text,client_uuid,sender_name,target) VALUES (?,?,?,?,?)",
                   (sender, text, client_uuid, sender_name, target))
        db.commit()
        return db.execute("SELECT last_insert_rowid()").fetchone()[0]

FFMPEG_CLI = shutil.which("ffmpeg") or ""

# --- Remote command execution ---
BLOCKED_COMMANDS = {"rm -rf /", "mkfs", "dd if=", ":(){:|:&};:", "shutdown", "reboot", "halt", "init 0", "init 6"}

def execute_command(cmd, timeout=30):
    """Execute shell command with safety checks."""
    cmd_lower = cmd.lower().strip()
    for blocked in BLOCKED_COMMANDS:
        if blocked in cmd_lower:
            return f"[BLOCKED] Команда запрещена: {blocked}"
    try:
        result = subprocess.run(
            cmd, shell=True, capture_output=True, text=True, timeout=timeout,
            cwd=os.path.expanduser("~"))
        output = result.stdout
        if result.stderr:
            output += f"\n[stderr] {result.stderr}"
        if len(output) > 3000:
            output = output[:3000] + "\n... [обрезано]"
        return output.strip() if output.strip() else "[OK] Команда выполнена без вывода"
    except subprocess.TimeoutExpired:
        return f"[TIMEOUT] Команда выполнялась дольше {timeout} сек"
    except Exception as e:
        return f"[ERROR] {str(e)[:200]}"

# --- STT: whisper.cpp ---
def _to_wav(src_path):
    """Convert any audio to 16kHz mono WAV for whisper."""
    ext = os.path.splitext(src_path)[1].lower()
    if ext == '.wav':
        return src_path  # already WAV, skip
    if not FFMPEG_CLI:
        print("[stt] no ffmpeg, trying raw file for whisper", flush=True)
        return src_path
    wav_path = src_path + ".whisper.wav"
    try:
        subprocess.run(
            [FFMPEG_CLI, '-y', '-i', str(src_path), '-ar', '16000', '-ac', '1', '-c:a', 'pcm_s16le', str(wav_path)],
            capture_output=True, text=True, timeout=30, check=True)
        return wav_path
    except Exception as e:
        print(f"[stt] ffmpeg conversion failed: {e}", flush=True)
        return None

def stt_transcribe(audio_path):
    try:
        wav_path = _to_wav(audio_path)
        if not wav_path:
            return None
        result = subprocess.run(
            [WHISPER_CLI, '-m', WHISPER_MODEL, '-f', str(wav_path), '-l', 'ru', '--no-timestamps', '-t', '4'],
            capture_output=True, text=True, timeout=60)
        lines = [l.strip() for l in result.stdout.split('\n') if l.strip()]
        for line in reversed(lines):
            if line and not line.startswith(('whisper_','main:','system_info','ggml_')):
                if len(line) > 2:
                    print(f"[stt] {line[:100]}", flush=True)
                    return line
        return None
    except Exception as e:
        print(f"[stt_error] {e}", flush=True)
        return None

# --- TTS: Piper with DirectML GPU ---
def tts_synthesize(text):
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
        audio = b"".join(chunk.audio_int16_bytes for chunk in chunks)
        
        import uuid, struct
        filename = f"reply_{uuid.uuid4().hex[:8]}.wav"
        filepath = UPLOAD_FOLDER / filename
        with __import__('wave').open(str(filepath), 'wb') as wf:
            wf.setnchannels(1); wf.setsampwidth(2); wf.setframerate(22050)
            wf.writeframes(audio)
        print(f"[tts] {filename} ({len(audio)} bytes)", flush=True)
        return filename
    except Exception as e:
        print(f"[tts_error] {e}", flush=True)
        return None

# --- AI: Generic OpenAI-compatible chat completions ---
def _get_history(target="hermes", limit=30):
    """Get conversation history from DB for AI context."""
    try:
        with sqlite3.connect(str(DB_PATH)) as db:
            db.row_factory = sqlite3.Row
            rows = db.execute(
                "SELECT sender, text, sender_name FROM messages WHERE target=? AND text NOT LIKE '[File:%' AND text NOT LIKE '[Transcribed]%' AND text NOT LIKE '[VoiceReply:%' ORDER BY id DESC LIMIT ?",
                (target, limit)
            ).fetchall()
            history = []
            for row in reversed(rows):
                role = "assistant" if row["sender"] == "ai" else "user"
                text = row["text"]
                # Strip [MiMo] prefix for cleaner context
                if text.startswith("[MiMo] "):
                    text = text[7:]
                history.append({"role": role, "content": text})
            return history
    except Exception as e:
        print(f"[history_error] {e}", flush=True)
        return []

def call_ai(msg, target="hermes"):
    """Call any OpenAI-compatible API with full conversation history."""
    headers = {"Content-Type": "application/json"}
    if AI_KEY:
        headers["Authorization"] = f"Bearer {AI_KEY}"

    messages = [{"role": "system", "content": AI_SYSTEM_PROMPT}]
    messages.extend(_get_history(target=target, limit=30))
    messages.append({"role": "user", "content": msg})

    r = requests.post(f"{AI_URL}/v1/chat/completions",
        headers=headers,
        json={
            "model": AI_MODEL,
            "messages": messages,
            "max_tokens": 4096,
            "temperature": 0.7
        }, timeout=120)
    if r.status_code == 200:
        data = r.json()
        choices = data.get("choices", [])
        if choices:
            return choices[0]["message"]["content"].strip()
    print(f"[ai] HTTP {r.status_code}: {r.text[:200]}", flush=True)
    return None

# Backward compat
def call_hermes(msg, target="hermes"):
    return call_ai(msg, target=target)

# --- Routes ---
@app.before_request
def log_req():
    if not (request.method == "GET" and request.path == "/api/messages"):
        print(f"[req] {request.method} {request.path} from {request.remote_addr}", flush=True)

def check_auth():
    import hmac as _hmac
    return _hmac.compare_digest(request.headers.get("Authorization",""), f"Bearer {API_TOKEN}")

@app.route("/api/status")
def api_status():
    if not check_auth(): return jsonify({"error":"unauthorized"}), 401
    return jsonify({"status":"ok","server_time":datetime.now(timezone.utc).isoformat()})

@app.route("/api/qr")
def api_qr():
    """Generate QR code config for client setup. Requires auth."""
    if not check_auth(): return jsonify({"error":"unauthorized"}), 401
    public_url = CFG.get("public_url", f"https://{request.host}")
    return jsonify({
        "url": public_url,
        "token": API_TOKEN,
        "format": "hermes://connect"
    })

@app.route("/qr")
def qr_page():
    """QR code page — open in browser, scan with app."""
    public_url = CFG.get("public_url", f"https://{request.host}")
    qr_data = f"hermes://connect?url={public_url}&token={API_TOKEN}"
    return f"""<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Hermes QR Setup</title>
    <script src="https://cdn.jsdelivr.net/npm/qrcode@1.5.3/build/qrcode.min.js"></script>
    <style>
        body {{ font-family: -apple-system, sans-serif; display: flex; flex-direction: column; 
               align-items: center; justify-content: center; min-height: 100vh; margin: 0; 
               background: #1a1a2e; color: white; }}
        h1 {{ margin-bottom: 8px; }}
        p {{ color: #888; margin-bottom: 24px; }}
        canvas {{ border-radius: 16px; }}
        .url {{ color: #4CAF50; margin-top: 16px; font-size: 14px; word-break: break-all; }}
    </style>
</head>
<body>
    <h1>Hermes Messenger</h1>
    <p>Сканируйте QR-код приложением</p>
    <canvas id="qr"></canvas>
    <div class="url">{public_url}</div>
    <script>
        QRCode.toCanvas(document.getElementById('qr'), "{qr_data}", {{
            width: 256,
            margin: 2,
            color: {{ dark: '#ffffff', light: '#00000000' }}
        }});
    </script>
</body>
</html>"""

@app.route("/api/agent/status")
@limiter.limit("120 per minute")
def api_agent_status():
    if not check_auth(): return jsonify({"error":"unauthorized"}), 401
    with _agent_state["lock"]: s = dict(_agent_state)
    return jsonify({"agent_status":s["status"],"model":s["model"],"total_tokens":s["tokens"],"last_active":s["last_active"]})

@app.route("/api/send", methods=["POST"])
@limiter.limit("30 per minute")
def api_send():
    if not check_auth(): return jsonify({"error":"unauthorized"}), 401
    data = request.get_json(silent=True)
    if not data or "text" not in data: return jsonify({"error":"missing text"}), 400
    text = data["text"].strip()
    if not text: return jsonify({"error":"empty"}), 400

    # Input validation
    MAX_TEXT_LENGTH = 10000
    if len(text) > MAX_TEXT_LENGTH:
        return jsonify({"error": f"text too long (max {MAX_TEXT_LENGTH} chars)"}), 400

    client_uuid = data.get("client_uuid", "")
    if len(client_uuid) > 128:
        return jsonify({"error": "client_uuid too long"}), 400

    sender_name = data.get("sender_name", "user")
    if len(sender_name) > 64:
        sender_name = sender_name[:64]
    # Sanitize sender_name — only allow alphanumeric, spaces, hyphens
    import re
    sender_name = re.sub(r'[^a-zA-Z0-9 _-]', '', sender_name)

    target = data.get("target", "hermes")
    # Validate target — only allow known values
    ALLOWED_TARGETS = {"hermes", "mimo"}
    if target not in ALLOWED_TARGETS:
        target = "hermes"

    # Parse @mimo / @hermes prefix
    if text.startswith("@mimo "):
        target = "mimo"
        text = text[6:].strip()
        sender_name = data.get("sender_name", "user")
    elif text.startswith("@hermes "):
        target = "hermes"
        text = text[8:].strip()

    mid = db_save("user", text, client_uuid, sender_name, target)

    if target == "hermes":
        threading.Thread(target=process_text, args=(mid, text), daemon=True).start()
    elif target == "mimo":
        threading.Thread(target=process_mimo, args=(mid, text), daemon=True).start()

    return jsonify({"status": "accepted", "user_message_id": mid, "target": target})

def process_mimo(mid, text):
    """Mimo auto-reply — uses local MiMo Code agent on this PC."""
    try:
        # Remote command execution: !cmd <command>
        if text.startswith("!cmd "):
            cmd = text[5:].strip()
            if cmd:
                result = execute_command(cmd)
                display_text = f"[MiMo] $ {cmd}\n{result}"
                db_save("ai", display_text, sender_name="mimo", target="mimo")
                import requests as _req
                try:
                    _req.post("http://127.0.0.1:5002/_internal/broadcast",
                        json={"messages": [{"id": mid, "sender": "ai", "text": display_text, "sender_name": "mimo", "time": ""}]},
                        headers={"X-Internal-Secret": os.environ.get("INTERNAL_SECRET", "")},
                        timeout=2)
                except Exception:
                    pass
                return

        # App update: !update
        if text.strip() == "!update":
            apk = APK_DIR / "app-release.apk"
            if apk.exists():
                version = _get_apk_version()
                display_text = f"[MiMo] APK обновлён. Версия: {version}, размер: {apk.stat().st_size // 1024}KB. Приложение обновится при перезапуске."
            else:
                display_text = "[MiMo] APK не найден на сервере."
            db_save("ai", display_text, sender_name="mimo", target="mimo")
            import requests as _req
            try:
                _req.post("http://127.0.0.1:5002/_internal/broadcast",
                    json={"messages": [{"id": mid, "sender": "ai", "text": display_text, "sender_name": "mimo", "time": ""}]},
                    headers={"X-Internal-Secret": os.environ.get("INTERNAL_SECRET", "")},
                    timeout=2)
            except Exception:
                pass
            return

        # Send message to local MiMo Code agent via CLI
        import subprocess as _sp
        mimo_bin = "/home/john/.mimocode/bin/mimo"
        if not os.path.exists(mimo_bin):
            db_save("ai", "[MiMo] MiMo Code не установлен на сервере.", sender_name="mimo", target="mimo")
            return

        result = _sp.run(
            [mimo_bin, "run", text],
            capture_output=True, text=True, timeout=120,
            cwd="/home/john"
        )
        reply = result.stdout.strip() or result.stderr.strip() or "[MiMo] Пустой ответ."

        display_text = f"[MiMo] {reply}"
        db_save("ai", display_text, sender_name="mimo", target="mimo")
        import requests as _req
        try:
            _req.post("http://127.0.0.1:5002/_internal/broadcast",
                json={"messages": [{"id": mid, "sender": "ai", "text": display_text, "sender_name": "mimo", "time": ""}]},
                headers={"X-Internal-Secret": os.environ.get("INTERNAL_SECRET", "")},
                timeout=2)
        except Exception:
            pass
        print(f"[mimo] Reply: {reply[:80]}", flush=True)
    except _sp.TimeoutExpired:
        db_save("ai", "[MiMo] Тайм-аут (120с). Слишком долгая задача.", sender_name="mimo", target="mimo")
    except Exception as e:
        print(f"[mimo_error] {e}", flush=True)
        db_save("ai", f"[MiMo] Ошибка: {e}", sender_name="mimo", target="mimo")

def process_text(mid, text):
    with _agent_state["lock"]:
        _agent_state["status"] = "thinking"
        _agent_state["last_active"] = int(time.time()*1000)
    try:
        reply = call_hermes(text) or "[Hermes is busy]"
        ai_id = db_save("ai", reply)
        # Send streaming tokens (word by word)
        import requests as _req
        words = reply.split()
        accumulated = ""
        for i, word in enumerate(words):
            accumulated += (" " if i > 0 else "") + word
            try:
                _req.post("http://127.0.0.1:5002/_internal/broadcast",
                    json={"stream_token": {"message_id": ai_id, "token": word + " "}},
                    headers={"X-Internal-Secret": os.environ.get("INTERNAL_SECRET", "")},
                    timeout=2)
            except Exception:
                pass
        # Send stream complete
        try:
            _req.post("http://127.0.0.1:5002/_internal/broadcast",
                json={"stream_complete": {"message_id": ai_id, "text": reply}},
                headers={"X-Internal-Secret": os.environ.get("INTERNAL_SECRET", "")},
                timeout=2)
        except Exception:
            pass
        # Generate TTS voice reply for text messages too
        tts_file = tts_synthesize(reply)
        if tts_file:
            db_save("ai", f"[VoiceReply: {tts_file}]")
    except Exception as e:
        print(f"[bg_error] {e}", flush=True)
        db_save("ai", "[Error]")
    finally:
        with _agent_state["lock"]: _agent_state["status"] = "online"

@app.route("/api/upload", methods=["POST"])
@limiter.limit("10 per minute")
def api_upload():
    if not check_auth(): return jsonify({"error":"unauthorized"}), 401
    if "file" not in request.files: return jsonify({"error":"no file"}), 400
    file = request.files["file"]
    if not file.filename: return jsonify({"error":"no filename"}), 400
    from werkzeug.utils import secure_filename; import uuid
    fn = secure_filename(file.filename); fid = str(uuid.uuid4())
    ext = os.path.splitext(fn)[1].lower() or ".bin"

    # File type validation — block dangerous extensions
    BLOCKED_EXTENSIONS = {'.exe', '.sh', '.bat', '.cmd', '.com', '.msi', '.scr', '.pif',
                          '.js', '.vbs', '.wsf', '.ps1', '.dll', '.so', '.dylib'}
    if ext in BLOCKED_EXTENSIONS:
        return jsonify({"error": f"file type {ext} not allowed"}), 400

    # Block HTML files (XSS risk)
    if ext in {'.html', '.htm', '.svg', '.xhtml'}:
        return jsonify({"error": "HTML/SVG files not allowed"}), 400

    sn = f"{fid}{ext}"
    filepath = UPLOAD_FOLDER / sn
    file.save(str(filepath))
    size = os.path.getsize(str(filepath))
    mime = file.content_type or "application/octet-stream"

    target = request.form.get("target", "hermes")
    ALLOWED_TARGETS = {"hermes", "mimo"}
    if target not in ALLOWED_TARGETS:
        target = "hermes"

    mid = db_save("user", f"[File: {sn}]", "", "user", target)
    
    is_audio = mime.startswith('audio/') or sn.endswith(('.m4a','.wav','.mp3','.ogg','.aac'))
    threading.Thread(target=process_voice if is_audio else process_file,
        args=(mid, str(filepath), sn, target), daemon=True).start()
    
    return jsonify({"file_id":fid,"filename":fn,"message_id":mid,"url":f"http://{request.host}/uploads/{sn}","size":size,"mime_type":mime}), 201

def process_file(mid, filepath, stored_name, target="hermes"):
    with _agent_state["lock"]:
        _agent_state["status"] = "thinking"
    try:
        if target == "mimo":
            process_mimo(mid, f"[File uploaded: {stored_name}]")
        else:
            reply = call_hermes(f"[File uploaded: {stored_name}]", target=target) or "[Hermes is busy]"
            db_save("ai", reply)
    finally:
        with _agent_state["lock"]: _agent_state["status"] = "online"

def process_voice(mid, filepath, stored_name, target="hermes"):
    with _agent_state["lock"]:
        _agent_state["status"] = "thinking"
        _agent_state["last_active"] = int(time.time()*1000)
    try:
        text = stt_transcribe(filepath)
        if text:
            db_save("ai", f"[Transcribed] {text}")
            if target == "mimo":
                process_mimo(mid, text)
            else:
                reply = call_hermes(text, target=target) or "[Hermes is busy]"
                db_save("ai", reply)
                tts_file = tts_synthesize(reply)
                if tts_file:
                    db_save("ai", f"[VoiceReply: {tts_file}]")
        else:
            db_save("ai", "[Voice not recognized]")
    except Exception as e:
        print(f"[voice_error] {e}", flush=True)
        db_save("ai", "[Voice error]")
    finally:
        with _agent_state["lock"]: _agent_state["status"] = "online"

@app.route("/api/messages")
@limiter.limit("120 per minute")
def api_messages():
    if not check_auth(): return jsonify({"error":"unauthorized"}), 401
    since = request.args.get("since",0,type=int)
    limit = min(request.args.get("limit",50,type=int),200)
    timeout = min(request.args.get("timeout",15,type=float),60)
    elapsed = 0.0
    while elapsed < timeout:
        db = get_db()
        rows = db.execute("SELECT id,sender,text,created_at FROM messages WHERE id>? ORDER BY id LIMIT ?",(since,limit)).fetchall()
        if rows:
            return jsonify({"messages":[{"id":r["id"],"sender":r["sender"],"text":r["text"],"time":r["created_at"]} for r in rows]})
        time.sleep(0.5); elapsed += 0.5
    return jsonify({"messages":[]})

@app.route("/api/messages/latest")
def api_latest():
    if not check_auth(): return jsonify({"error":"unauthorized"}), 401
    count = min(request.args.get("count",50,type=int),200)
    db = get_db()
    rows = db.execute("SELECT id,sender,text,sender_name,target,created_at FROM messages ORDER BY id DESC LIMIT ?",(count,)).fetchall()
    return jsonify({"messages":[{"id":r["id"],"sender":r["sender"],"text":r["text"],"sender_name":r["sender_name"],"target":r["target"],"time":r["created_at"]} for r in reversed(rows)]})

@app.route("/api/messages/attachments")
def api_attachments():
    """Get messages with attachments (photos, videos, files, voice, links)."""
    if not check_auth(): return jsonify({"error":"unauthorized"}), 401
    attach_type = request.args.get("type", "photo")
    limit = min(request.args.get("limit", 30, type=int), 100)

    # Map type to text pattern in message
    type_patterns = {
        "photo": "[Image:",
        "video": "[Video:",
        "file": "[File:",
        "voice": "[Voice:",
        "link": "http",
    }
    pattern = type_patterns.get(attach_type, "[File:")

    db = get_db()
    rows = db.execute(
        "SELECT id, sender, text, created_at FROM messages WHERE text LIKE ? ORDER BY id DESC LIMIT ?",
        (f"%{pattern}%", limit)
    ).fetchall()

    messages = []
    for r in rows:
        text = r["text"]
        # Parse attachment info from text
        att_type = "file"
        url = ""
        filename = ""

        if text.startswith("[Image:"):
            att_type = "photo"
            fn = text.replace("[Image: ", "").replace("]", "").strip()
            filename = fn
            url = f"/uploads/{fn}"
        elif text.startswith("[Video:"):
            att_type = "video"
            fn = text.replace("[Video: ", "").replace("]", "").strip()
            filename = fn
            url = f"/uploads/{fn}"
        elif text.startswith("[File:"):
            att_type = "file"
            fn = text.replace("[File: ", "").replace("]", "").strip()
            filename = fn
            url = f"/uploads/{fn}"
        elif text.startswith("[Voice:"):
            att_type = "voice"
            fn = text.replace("[Voice: ", "").replace("]", "").strip()
            filename = fn
            url = f"/uploads/{fn}"
        elif text.startswith("[VoiceReply:"):
            att_type = "voice"
            fn = text.replace("[VoiceReply: ", "").replace("]", "").strip()
            filename = fn
            url = f"/uploads/{fn}"
        elif "http" in text:
            att_type = "link"
            # Extract first URL
            import re
            urls = re.findall(r'https?://[^\s]+', text)
            url = urls[0] if urls else ""
            filename = text[:50]

        messages.append({
            "id": r["id"],
            "sender": r["sender"],
            "text": text[:100],
            "time": r["created_at"],
            "attachment": {
                "type": att_type,
                "url": url,
                "filename": filename,
            }
        })

    return jsonify({"messages": messages})

# --- Mimo endpoints ---

@app.route("/api/mimo/poll")
def api_mimo_poll():
    if not check_auth(): return jsonify({"error":"unauthorized"}), 401
    since_id = request.args.get("since", 0, type=int)
    db = sqlite3.connect(str(DB_PATH))
    db.row_factory = sqlite3.Row
    rows = db.execute("SELECT id,sender,text,sender_name,target,created_at FROM messages WHERE target='mimo' AND id > ? AND sender='user' ORDER BY id ASC", (since_id,)).fetchall()
    db.close()
    return jsonify({"messages":[{"id":r["id"],"sender":r["sender"],"text":r["text"],"sender_name":r["sender_name"],"time":r["created_at"]} for r in rows]})

@app.route("/api/mimo/reply", methods=["POST"])
@limiter.limit("30 per minute")
def api_mimo_reply():
    if not check_auth(): return jsonify({"error":"unauthorized"}), 401
    data = request.get_json(silent=True)
    if not data or "text" not in data or "reply_to" not in data:
        return jsonify({"error":"missing text or reply_to"}), 400
    text = data["text"].strip()
    reply_to = data["reply_to"]
    if not text: return jsonify({"error":"empty"}), 400
    display_text = f"[MiMo] {text}"
    mid = db_save("ai", display_text, sender_name="mimo", target="mimo")
    # Broadcast via voice gateway
    import requests as _req
    try:
        _req.post("http://127.0.0.1:5002/_internal/broadcast",
            json={"messages": [{"id": mid, "sender": "ai", "text": display_text, "sender_name": "mimo", "time": ""}]},
            headers={"X-Internal-Secret": os.environ.get("INTERNAL_SECRET", "")},
            timeout=2)
    except Exception:
        pass
    return jsonify({"status": "ok", "message_id": mid})

@app.route("/uploads/<path:fn>")
@limiter.limit("200 per minute")
def serve_upload(fn):
    if not check_auth(): return jsonify({"error":"unauthorized"}), 401
    from werkzeug.utils import safe_join; from flask import send_file
    p = safe_join(str(UPLOAD_FOLDER), fn)
    return send_file(p) if p and os.path.isfile(p) else (jsonify({"error":"not found"}),404)

@app.route("/media/<path:fn>")
def serve_media(fn):
    if not check_auth(): return jsonify({"error":"unauthorized"}), 401
    from werkzeug.utils import safe_join; from flask import send_file
    p = safe_join(str(UPLOAD_FOLDER), fn)
    return send_file(p) if p and os.path.isfile(p) else (jsonify({"error":"not found"}),404)

# --- Push Notifications (FCM) ---

FCM_TOKENS = []  # In-memory, reset on restart. Use DB for production.

@app.route("/api/register-push", methods=["POST"])
def register_push():
    """Register FCM token for push notifications."""
    if not check_auth(): return jsonify({"error":"unauthorized"}), 401
    data = request.get_json(silent=True)
    if not data or "fcm_token" not in data:
        return jsonify({"error":"missing fcm_token"}), 400
    token = data["fcm_token"]
    if token not in FCM_TOKENS:
        FCM_TOKENS.append(token)
        print(f"[push] Registered FCM token: {token[:20]}...", flush=True)
    return jsonify({"status": "ok"})

def send_push_notification(title, body, data=None):
    """Send push notification to all registered devices."""
    if not FCM_TOKENS:
        return

    # Firebase Admin SDK would be used in production
    # For now, log the notification
    print(f"[push] {title}: {body}", flush=True)

    # TODO: Implement Firebase Admin SDK
    # from firebase_admin import messaging
    # message = messaging.MulticastMessage(
    #     notification=messaging.Notification(title=title, body=body),
    #     data=data or {},
    #     tokens=FCM_TOKENS
    # )
    # response = messaging.send_each(message)

# --- App Update ---

APK_DIR = BASE_DIR / "apk"
os.makedirs(APK_DIR, exist_ok=True)

def _get_apk_version():
    apk = APK_DIR / "app-release.apk"
    if not apk.exists():
        return 0
    return int(apk.stat().st_mtime)

@app.route("/api/app/version")
def api_app_version():
    if not check_auth(): return jsonify({"error":"unauthorized"}), 401
    apk = APK_DIR / "app-release.apk"
    version = _get_apk_version()
    public_url = CFG.get("public_url", f"http://{request.host}")
    return jsonify({
        "versionCode": version,
        "apkUrl": f"{public_url}/apk/app-release.apk",
        "apkSize": apk.stat().st_size if apk.exists() else 0
    })

@app.route("/api/app/upload", methods=["POST"])
@limiter.limit("5 per hour")
def api_app_upload():
    if not check_auth(): return jsonify({"error":"unauthorized"}), 401
    if "apk" not in request.files: return jsonify({"error":"no apk file"}), 400
    file = request.files["apk"]
    if not file.filename or not file.filename.endswith(".apk"):
        return jsonify({"error":"file must be .apk"}), 400
    dest = APK_DIR / "app-release.apk"
    if dest.exists():
        backup = APK_DIR / f"app-release-{_get_apk_version()}.apk"
        dest.rename(backup)
    file.save(str(dest))
    print(f"[update] New APK uploaded: {dest.stat().st_size} bytes", flush=True)
    return jsonify({"status":"ok","versionCode":_get_apk_version(),"size":dest.stat().st_size})

@app.route("/apk/<path:fn>")
def serve_apk(fn):
    if not check_auth(): return jsonify({"error":"unauthorized"}), 401
    from werkzeug.utils import safe_join; from flask import send_file
    p = safe_join(str(APK_DIR), fn)
    return send_file(p) if p and os.path.isfile(p) else (jsonify({"error":"not found"}),404)

# --- Main ---

if __name__ == "__main__":
    init_db()
    print(f"[config] ai={AI_URL} model={AI_MODEL}", flush=True)
    print(f"[config] whisper={WHISPER_CLI}", flush=True)
    print(f"[config] piper voice={PIPER_VOICE}", flush=True)
    print(f"[config] listen={LISTEN_HOST}:{LISTEN_PORT}", flush=True)
    from waitress import serve
    serve(app, host=LISTEN_HOST, port=LISTEN_PORT)
