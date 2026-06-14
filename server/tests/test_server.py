"""Automated tests for Hermes Messenger server."""
import os
import json
import pytest
import sys
import tempfile
sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))

# Set test environment before importing server
os.environ["API_TOKEN"] = "test-token-123"
os.environ["LISTEN_HOST"] = "127.0.0.1"
os.environ["LISTEN_PORT"] = "5099"
os.environ["AI_URL"] = "http://127.0.0.1:11434"
os.environ["AI_KEY"] = ""
os.environ["AI_MODEL"] = "test-model"

from server import app, init_db, db_save, API_TOKEN, DB_PATH

@pytest.fixture
def client():
    app.config["TESTING"] = True
    # Use temp DB for tests
    import server
    orig_db = server.DB_PATH
    with tempfile.NamedTemporaryFile(suffix=".db", delete=False) as f:
        test_db = f.name
    server.DB_PATH = test_db
    init_db()
    with app.test_client() as client:
        yield client
    os.unlink(test_db)
    server.DB_PATH = orig_db

@pytest.fixture
def auth_headers():
    return {"Authorization": f"Bearer {API_TOKEN}"}

def test_status_unauthorized(client):
    r = client.get("/api/status")
    assert r.status_code == 401

def test_status_authorized(client, auth_headers):
    r = client.get("/api/status", headers=auth_headers)
    assert r.status_code == 200
    data = r.get_json()
    assert data["status"] == "ok"

def test_send_no_auth(client):
    r = client.post("/api/send", json={"text": "hello"})
    assert r.status_code == 401

def test_send_no_text(client, auth_headers):
    r = client.post("/api/send", json={}, headers=auth_headers)
    assert r.status_code == 400
    assert "missing text" in r.get_json()["error"]

def test_send_empty_text(client, auth_headers):
    r = client.post("/api/send", json={"text": "  "}, headers=auth_headers)
    assert r.status_code == 400

def test_send_too_long(client, auth_headers):
    r = client.post("/api/send", json={"text": "A" * 10001}, headers=auth_headers)
    assert r.status_code == 400
    assert "too long" in r.get_json()["error"]

def test_send_valid(client, auth_headers):
    r = client.post("/api/send", json={"text": "test message", "client_uuid": "test-001"}, headers=auth_headers)
    assert r.status_code == 200
    data = r.get_json()
    assert data["status"] == "accepted"
    assert "user_message_id" in data

def test_send_invalid_target(client, auth_headers):
    r = client.post("/api/send", json={"text": "test", "target": "evil"}, headers=auth_headers)
    assert r.status_code == 200
    assert r.get_json()["target"] == "hermes"  # falls back to hermes

def test_send_duplicate_uuid(client, auth_headers):
    r1 = client.post("/api/send", json={"text": "first", "client_uuid": "dup-001"}, headers=auth_headers)
    r2 = client.post("/api/send", json={"text": "second", "client_uuid": "dup-001"}, headers=auth_headers)
    assert r1.get_json()["user_message_id"] == r2.get_json()["user_message_id"]

def test_messages_latest(client, auth_headers):
    # First send a message
    client.post("/api/send", json={"text": "hello"}, headers=auth_headers)
    r = client.get("/api/messages/latest?count=1", headers=auth_headers)
    assert r.status_code == 200
    assert "messages" in r.get_json()

def test_upload_no_file(client, auth_headers):
    r = client.post("/api/upload", headers=auth_headers)
    assert r.status_code == 400

def test_upload_blocked_extension(client, auth_headers):
    from io import BytesIO
    data = {"file": (BytesIO(b"test content"), "malware.exe")}
    r = client.post("/api/upload", data=data, content_type="multipart/form-data", headers=auth_headers)
    assert r.status_code == 400
    assert "not allowed" in r.get_json()["error"]

def test_qr_requires_auth(client):
    r = client.get("/api/qr")
    assert r.status_code == 401

def test_qr_authorized(client, auth_headers):
    r = client.get("/api/qr", headers=auth_headers)
    assert r.status_code == 200
    data = r.get_json()
    assert "url" in data
    assert "token" in data

def test_qr_page(client):
    r = client.get("/qr")
    assert r.status_code == 200
    assert b"hermes" in r.data.lower()
