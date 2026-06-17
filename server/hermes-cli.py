#!/usr/bin/env python3
"""Hermes CLI — отправка сообщений из терминала в Hermes Messenger."""
import sys, json, time, requests
from pathlib import Path

SERVER = "http://127.0.0.1:5001"
TOKEN = None

def load_token():
    global TOKEN
    try:
        env_path = Path(__file__).resolve().parent / ".env"
        for line in env_path.read_text().splitlines():
            line = line.strip()
            if line.startswith("API_TOKEN="):
                TOKEN = line.split("=", 1)[1].strip().strip('"').strip("'")
                return
    except Exception:
        pass
    if not TOKEN:
        try:
            TOKEN = input("API_TOKEN: ").strip()
        except EOFError:
            TOKEN = ""

def send(text, target="hermes", sender_name="cli"):
    r = requests.post(f"{SERVER}/api/send",
        headers={"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"},
        json={"text": text, "sender_name": sender_name, "target": target},
        timeout=10)
    return r.json()

def poll(since=0, timeout=30):
    r = requests.get(f"{SERVER}/api/messages",
        headers={"Authorization": f"Bearer {TOKEN}"},
        params={"since": since, "limit": 50, "timeout": timeout},
        timeout=timeout+10)
    return r.json().get("messages", [])

def main():
    load_token()
    print(f"[hermes-cli] Подключение к {SERVER}")
    print("[hermes-cli] Команды: @mimo текст — в MiMo, @hermes текст — в Hermes, /history — история, /quit — выход")
    print()

    last_id = 0
    # Загрузить последние сообщения для контекста
    try:
        msgs = poll(since=0, timeout=1)
        if msgs:
            last_id = msgs[-1]["id"]
            print(f"[загружено {len(msgs)} сообщений, последний ID: {last_id}]")
            for m in msgs[-5:]:
                prefix = "[AI]" if m["sender"] == "ai" else "[You]"
                print(f"  {prefix} {m['text'][:80]}")
            print()
    except Exception as e:
        print(f"[warn] не удалось загрузить историю: {e}")

    while True:
        try:
            text = input("you> ").strip()
        except (EOFError, KeyboardInterrupt):
            print("\n[выход]")
            break

        if not text:
            continue
        if text == "/quit":
            break
        if text == "/history":
            try:
                msgs = poll(since=0, timeout=1)
                for m in msgs[-20:]:
                    prefix = "[AI]" if m["sender"] == "ai" else "[You]"
                    print(f"  {prefix} {m['text'][:100]}")
            except Exception as e:
                print(f"[error] {e}")
            continue

        target = "hermes"
        if text.startswith("@mimo "):
            target = "mimo"
            text = text[6:].strip()
        elif text.startswith("@hermes "):
            target = "hermes"
            text = text[8:].strip()

        try:
            result = send(text, target=target)
            if "error" in result:
                print(f"[error] {result['error']}")
                continue
            mid = result.get("user_message_id", 0)
            print(f"[отправлено в {target}, id={mid}]")

            # Ждём ответ AI
            print("[ждём ответ...]", end="", flush=True)
            replied = False
            start = time.time()
            while time.time() - start < 120:
                msgs = poll(since=mid, timeout=5)
                ai_msgs = [m for m in msgs if m["sender"] == "ai" and m["id"] > mid]
                if ai_msgs:
                    for m in ai_msgs:
                        text_out = m["text"]
                        if text_out.startswith("[VoiceReply:"):
                            continue
                        print(f"\r{m.get('sender_name','ai')}> {text_out}")
                    last_id = max(m["id"] for m in msgs) if msgs else last_id
                    replied = True
                    break
                print(".", end="", flush=True)
            if not replied:
                print("\n[тайм-аут — ответ не получен]")

        except requests.exceptions.ConnectionError:
            print(f"[error] сервер недоступен: {SERVER}")
        except Exception as e:
            print(f"[error] {e}")

if __name__ == "__main__":
    main()
