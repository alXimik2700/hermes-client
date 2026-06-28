# Hermes Messenger

[![CI](https://github.com/alXimik2700/hermes-client/actions/workflows/ci.yml/badge.svg)](https://github.com/alXimik2700/hermes-client/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/alXimik2700/hermes-client)](https://github.com/alXimik2700/hermes-client/releases)
[![License](https://img.shields.io/github/license/alXimik2700/hermes-client)](LICENSE)
[![Stars](https://img.shields.io/github/stars/alXimik2700/hermes-client)](https://github.com/alXimik2700/hermes-client/stargazers)

Offline-first Android voice messenger client for **Local AI** (Ollama, DeepSeek, LM Studio) via **Tailscale** private tunnels. Self-hosted, no cloud, full privacy.

## What it is

- **Android client** — Kotlin + Jetpack Compose, Room DB, WebSocket real-time sync
- **Python server** — Flask REST API + FastAPI WebSocket for voice streaming, connects to any OpenAI-compatible AI (Ollama, DeepSeek, LM Studio, vLLM)
- **Voice pipeline** — Edge-TTS (Microsoft Neural Voices) + whisper.cpp (STT), runs on your hardware
- **Knowledge graph** — LightRAG integration with local LLM for contextual AI memory
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
