# Hermes Messenger

Offline-first Android messenger with voice input/output, connecting to local AI servers via private tunnels. Built with AI agents, works without cloud services.

## What it is

- **Android client** — Kotlin + Jetpack Compose, Room DB for offline storage, WebSocket for real-time sync
- **Python server** — Flask REST API + FastAPI WebSocket for voice streaming, connects to any OpenAI-compatible AI
- **Voice pipeline** — whisper.cpp (STT) + Piper (TTS), runs on your hardware
- **Access from anywhere** — via Tailscale Funnel, no public server needed

## Quick start

### Server

```bash
cd server
cp .env.example .env
pip install -r requirements.txt
python3 server.py
```

### Android

```bash
cd android
# Add to local.properties:
#   API_TOKEN=your-token
#   SERVER_URL=https://your-server.tailnet.ts.net/
./gradlew assembleDebug
```

### QR setup

Open `https://your-server/qr` in browser, scan with app — done.

## Features

- Offline-first: messages saved locally, synced when online
- Voice: real-time STT/TTS via WebSocket
- Multi-agent: `@agent` prefix to switch between AI backends
- Universal AI: Ollama, vLLM, LM Studio, OpenAI, DeepSeek — any OpenAI-compatible API
- Security: token in Keystore, rate limiting, input validation

## Architecture

```
[Compose UI] ← Room DB ← [SyncService] → Tailscale → Flask :5001 → AI (Ollama/OpenAI/etc.)
                                                               ↓
                                            FastAPI :5002 ←→ whisper.cpp + Piper
```

## License

MIT
