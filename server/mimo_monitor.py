"""
MiMo Monitor — polls for @mimo messages and auto-replies.
Runs as systemd service, uses Xiaomi MiMo API directly.
"""
import os, sys, json, time, sqlite3, requests
from pathlib import Path

DB_PATH = Path(__file__).resolve().parent / "messages.db"
XIAOMI_API_KEY = os.environ.get("XIAOMI_API_KEY", "")
XIAOMI_BASE_URL = os.environ.get("XIAOMI_BASE_URL", "https://api.xiaomimimo.com/v1")
XIAOMI_MODEL = os.environ.get("XIAOMI_MODEL", "your-model-name")
API_TOKEN = os.environ.get("API_TOKEN", "")
POLL_INTERVAL = 5  # seconds

def get_pending_mimo(since_id):
    with sqlite3.connect(str(DB_PATH)) as db:
        db.row_factory = sqlite3.Row
        rows = db.execute(
            "SELECT id, text FROM messages WHERE target='mimo' AND sender='user' AND id > ? ORDER BY id ASC",
            (since_id,)
        ).fetchall()
        return [{"id": r["id"], "text": r["text"]} for r in rows]

def get_context():
    with sqlite3.connect(str(DB_PATH)) as db:
        db.row_factory = sqlite3.Row
        rows = db.execute(
            "SELECT sender, text FROM messages WHERE target='mimo' ORDER BY id DESC LIMIT 20"
        ).fetchall()
    messages = [{"role": "system", "content": "Ты MiMo Code — AI-ассистент от Xiaomi. Отвечай кратко и по делу. Используй русский язык. Не используй эмодзи. Помни контекст предыдущих сообщений."}]
    for row in reversed(rows):
        role = "assistant" if row["sender"] == "ai" else "user"
        msg_text = row["text"]
        if msg_text.startswith("[MiMo] "):
            msg_text = msg_text[7:]
        messages.append({"role": role, "content": msg_text})
    return messages

def reply(text, reply_to):
    with sqlite3.connect(str(DB_PATH)) as db:
        display_text = f"[MiMo] {text}"
        db.execute("INSERT INTO messages (sender, text, sender_name, target) VALUES (?, ?, ?, ?)",
                   ("ai", display_text, "mimo", "mimo"))
        db.commit()
        mid = db.execute("SELECT last_insert_rowid()").fetchone()[0]
    # Broadcast via voice gateway
    try:
        requests.post("http://127.0.0.1:5002/_internal/broadcast",
            json={"messages": [{"id": mid, "sender": "ai", "text": display_text, "sender_name": "mimo", "time": ""}]},
            headers={"X-Internal-Secret": os.environ.get("INTERNAL_SECRET", "")},
            timeout=2)
    except Exception:
        pass
    print(f"[mimo] Reply id={mid}: {text[:80]}", flush=True)

def process(msg_id, text):
    messages = get_context()
    try:
        r = requests.post(f"{XIAOMI_BASE_URL}/chat/completions",
            headers={
                "Authorization": f"Bearer {XIAOMI_API_KEY}",
                "Content-Type": "application/json"
            },
            json={"model": XIAOMI_MODEL, "messages": messages, "max_tokens": 1024, "temperature": 0.7},
            timeout=120)
        if r.status_code == 200:
            choices = r.json().get("choices", [])
            if choices:
                reply(choices[0]["message"]["content"].strip(), msg_id)
                return
        reply("Извини, не могу ответить сейчас.", msg_id)
    except Exception as e:
        print(f"[mimo_error] {e}", flush=True)
        reply("Ошибка соединения.", msg_id)

def main():
    print(f"[mimo_monitor] Starting, DB={DB_PATH}", flush=True)
    print(f"[mimo_monitor] Xiaomi MiMo API={XIAOMI_BASE_URL}", flush=True)
    print(f"[mimo_monitor] Model={XIAOMI_MODEL}", flush=True)
    last_id = 0
    # Start from latest message
    try:
        with sqlite3.connect(str(DB_PATH)) as db:
            row = db.execute("SELECT MAX(id) FROM messages WHERE target='mimo' AND sender='user'").fetchone()
            if row and row[0]:
                last_id = row[0]
    except Exception:
        pass
    print(f"[mimo_monitor] Starting from id={last_id}", flush=True)

    while True:
        try:
            pending = get_pending_mimo(last_id)
            for msg in pending:
                print(f"[mimo] New message id={msg['id']}: {msg['text'][:60]}", flush=True)
                process(msg["id"], msg["text"])
                last_id = msg["id"]
        except Exception as e:
            print(f"[mimo_monitor] Error: {e}", flush=True)
        time.sleep(POLL_INTERVAL)

if __name__ == "__main__":
    main()
