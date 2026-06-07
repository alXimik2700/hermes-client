import requests, json

# Copy config.json.example to config.json and fill in your values first
with open("config.json") as f:
    cfg = json.load(f)
token = cfg["api_token"]

# Test: send a message through the API
r = requests.post(
    f"http://127.0.0.1:{cfg['listen_port']}/api/send",
    headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
    json={"text": "Hello! What is your name?"},
    timeout=90,
)
print(f"Status: {r.status_code}")
data = r.json()
print(f"Reply: {json.dumps(data, indent=2, ensure_ascii=False)[:300]}")
