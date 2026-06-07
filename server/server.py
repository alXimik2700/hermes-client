"""
Hermes Messenger v3 - Full Voice Pipeline
STT: whisper.cpp (BLAS) | AI: Hermes session | TTS: Piper + DirectML GPU
"""
import os, sys, json, time, sqlite3, secrets, threading, subprocess, io, shutil
from datetime import datetime, timezone
from pathlib import Path
import requests
from flask import Flask, request, jsonify, g

BASE_DIR = Path(__file__).resolve().parent
DB_PATH = BASE_DIR / "messages.db"
CONFIG_PATH = BASE_DIR / "config.json"
UPLOAD_FOLDER = BASE_DIR / "uploads"
os.makedirs(UPLOAD_FOLDER, exist_ok=True)

# Load config
CFG = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))

# Voice pipeline paths
WHISPER_CLI = "/path/to/tool"
WHISPER_MODEL = "/path/to/tool"
PIPER_VOICE = "your-piper-voice"
PIPER_MODEL_DIR = "/path/to/tool"

HERMES_URL = CFG["hermes_api_url"]
HERMES_KEY = CFG["hermes_api_key"]
HERMES_SID = CFG.get("hermes_session_id", "")
API_TOKEN = CFG["api_token"]
LISTEN_HOST = CFG.get("listen_host", "0.0.0.0")
LISTEN_PORT = CFG.get("listen_port", 5000)

_agent_state = {"status": "online", "model": "", "tokens": 0, "last_active": 0, "lock": threading.Lock()}

app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = 50 * 1024 * 1024

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
        created_at TEXT NOT NULL DEFAULT (datetime('now')))""")
    db.execute("CREATE INDEX IF NOT EXISTS idx_msg_time ON messages(created_at)")
    db.commit(); db.close()

def db_save(sender, text):
    db = sqlite3.connect(str(DB_PATH))
    db.execute("INSERT INTO messages (sender,text) VALUES (?,?)", (sender, text))
    db.commit(); mid = db.execute("SELECT last_insert_rowid()").fetchone()[0]
    db.close(); return mid

FFMPEG_CLI = shutil.which("ffmpeg") or ""

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

# --- AI: Hermes stateless chat completions (no session dependency) ---
def call_hermes(msg):
    r = requests.post(f"{HERMES_URL}/v1/chat/completions",
        headers={"Authorization": f"Bearer {HERMES_KEY}", "Content-Type": "application/json"},
        json={
            "model": "your-model-name",
            "messages": [{"role": "user", "content": msg}],
            "max_tokens": 4096,
            "temperature": 0.7
        }, timeout=120)
    if r.status_code == 200:
        data = r.json()
        choices = data.get("choices", [])
        if choices:
            return choices[0]["message"]["content"].strip()
        return None
    print(f"[hermes] HTTP {r.status_code}", flush=True); return None

# --- Routes ---
@app.before_request
def log_req():
    if not (request.method == "GET" and request.path == "/api/messages"):
        print(f"[req] {request.method} {request.path} from {request.remote_addr}", flush=True)

def check_auth():
    return request.headers.get("Authorization","") == f"Bearer {API_TOKEN}"

@app.route("/api/status")
def api_status():
    if not check_auth(): return jsonify({"error":"unauthorized"}), 401
    return jsonify({"status":"ok","server_time":datetime.now(timezone.utc).isoformat()})

@app.route("/api/agent/status")
def api_agent_status():
    if not check_auth(): return jsonify({"error":"unauthorized"}), 401
    with _agent_state["lock"]: s = dict(_agent_state)
    return jsonify({"agent_status":s["status"],"model":s["model"],"total_tokens":s["tokens"],"last_active":s["last_active"]})

@app.route("/api/send", methods=["POST"])
def api_send():
    if not check_auth(): return jsonify({"error":"unauthorized"}), 401
    data = request.get_json(silent=True)
    if not data or "text" not in data: return jsonify({"error":"missing text"}), 400
    text = data["text"].strip()
    if not text: return jsonify({"error":"empty"}), 400
    mid = db_save("user", text)
    threading.Thread(target=process_text, args=(mid, text), daemon=True).start()
    return jsonify({"status":"accepted","user_message_id":mid})

def process_text(mid, text):
    with _agent_state["lock"]:
        _agent_state["status"] = "thinking"
        _agent_state["last_active"] = int(time.time()*1000)
    try:
        reply = call_hermes(text) or "[Hermes is busy]"
        db_save("ai", reply)
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
def api_upload():
    if not check_auth(): return jsonify({"error":"unauthorized"}), 401
    if "file" not in request.files: return jsonify({"error":"no file"}), 400
    file = request.files["file"]
    if not file.filename: return jsonify({"error":"no filename"}), 400
    from werkzeug.utils import secure_filename; import uuid
    fn = secure_filename(file.filename); fid = str(uuid.uuid4())
    ext = os.path.splitext(fn)[1] or ".bin"; sn = f"{fid}{ext}"
    filepath = UPLOAD_FOLDER / sn
    file.save(str(filepath))
    size = os.path.getsize(str(filepath))
    mime = file.content_type or "application/octet-stream"
    
    db = get_db()
    db.execute("INSERT INTO messages (sender,text) VALUES (?,?)",("user",f"[File: {sn}]"))
    mid = db.execute("SELECT last_insert_rowid()").fetchone()[0]
    db.commit()
    
    is_audio = mime.startswith('audio/') or sn.endswith(('.m4a','.wav','.mp3','.ogg','.aac'))
    threading.Thread(target=process_voice if is_audio else process_file,
        args=(mid, str(filepath), sn), daemon=True).start()
    
    return jsonify({"file_id":fid,"filename":fn,"message_id":mid,"url":f"http://{request.host}/uploads/{sn}","size":size,"mime_type":mime}), 201

def process_file(mid, filepath, stored_name):
    with _agent_state["lock"]:
        _agent_state["status"] = "thinking"
    try:
        reply = call_hermes(f"[File uploaded: {stored_name}]") or "[Hermes is busy]"
        db_save("ai", reply)
    finally:
        with _agent_state["lock"]: _agent_state["status"] = "online"

def process_voice(mid, filepath, stored_name):
    with _agent_state["lock"]:
        _agent_state["status"] = "thinking"
        _agent_state["last_active"] = int(time.time()*1000)
    try:
        text = stt_transcribe(filepath)
        if text:
            db_save("ai", f"[Transcribed] {text}")
            reply = call_hermes(text) or "[Hermes is busy]"
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
    rows = db.execute("SELECT id,sender,text,created_at FROM messages ORDER BY id DESC LIMIT ?",(count,)).fetchall()
    return jsonify({"messages":[{"id":r["id"],"sender":r["sender"],"text":r["text"],"time":r["created_at"]} for r in reversed(rows)]})

@app.route("/uploads/<path:fn>")
def serve_upload(fn):
    from werkzeug.utils import safe_join; from flask import send_file
    p = safe_join(str(UPLOAD_FOLDER), fn)
    return send_file(p) if p and os.path.isfile(p) else (jsonify({"error":"not found"}),404)

@app.route("/media/<path:fn>")
def serve_media(fn):
    from werkzeug.utils import safe_join; from flask import send_file
    p = safe_join(str(UPLOAD_FOLDER), fn)
    return send_file(p) if p and os.path.isfile(p) else (jsonify({"error":"not found"}),404)

if __name__ == "__main__":
    init_db()
    print(f"[config] hermes={HERMES_URL} session={HERMES_SID}", flush=True)
    print(f"[config] whisper={WHISPER_CLI}", flush=True)
    print(f"[config] piper voice={PIPER_VOICE}", flush=True)
    print(f"[config] listen={LISTEN_HOST}:{LISTEN_PORT}", flush=True)
    from waitress import serve
    serve(app, host=LISTEN_HOST, port=LISTEN_PORT)
