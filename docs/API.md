# Hermes Messenger API

Base URL: `https://your-server.tailnet.ts.net`

All endpoints require `Authorization: Bearer <token>` header unless noted.

## Endpoints

### Health Check

```
GET /api/status
```

**Response:**
```json
{"status": "ok", "server_time": "2026-01-01T00:00:00Z"}
```

### Send Message

```
POST /api/send
```

**Body:**
```json
{
  "text": "Hello!",
  "client_uuid": "optional-uuid-for-dedup",
  "sender_name": "user",
  "target": "hermes"
}
```

**Response:**
```json
{"status": "accepted", "user_message_id": 123, "target": "hermes"}
```

**Limits:** 30 requests/minute, text max 10,000 chars

### Upload File

```
POST /api/upload
Content-Type: multipart/form-data
```

**Form fields:**
- `file` — the file to upload
- `client_uuid` — optional dedup key

**Blocked types:** .exe, .sh, .bat, .js, .html, .svg

**Response:**
```json
{"file_id": "uuid", "filename": "photo.jpg", "message_id": 456, "url": "http://server/uploads/uuid.jpg", "size": 12345, "mime_type": "image/jpeg"}
```

**Limits:** 10 requests/minute, max 50MB

### Get Messages (Long-poll)

```
GET /api/messages?since=0&timeout=15
```

**Parameters:**
- `since` — message ID to start from
- `timeout` — long-poll timeout in seconds (max 60)

**Response:**
```json
{"messages": [{"id": 1, "sender": "ai", "text": "Hello!", "time": "2026-01-01T00:00:00Z"}]}
```

### Get Latest Messages

```
GET /api/messages/latest?count=50
```

### Agent Status

```
GET /api/agent/status
```

**Response:**
```json
{"agent_status": "online", "model": "llama3", "total_tokens": 1234, "last_active": 1704067200000}
```

### QR Code Config

```
GET /api/qr
```

**Response:**
```json
{"url": "https://your-server", "token": "your-token", "format": "hermes://connect"}
```

### QR Code Page

```
GET /qr
```

Returns HTML page with scannable QR code. No auth required for viewing.

## WebSocket (Voice Gateway)

Port 5002

### Voice Stream

```
ws://your-server:5002/api/voice-stream
```

**Authentication:** Send `{"token":"your-token"}` as first message.

**Protocol:**
1. Client sends audio chunks (binary PCM 16kHz)
2. Client sends `{"action":"end"}` to finish
3. Server responds with status JSON and TTS audio

### Message Stream

```
ws://your-server:5002/api/messages/stream
```

**Authentication:** Send `{"token":"your-token"}` as first message.

**Protocol:**
1. Client sends `{"since":0}` to catch up
2. Server sends missed messages
3. Server streams new messages in real-time

## Error Codes

- `401` — Unauthorized (missing or invalid token)
- `400` — Bad request (invalid input)
- `429` — Rate limit exceeded
- `500` — Server error
