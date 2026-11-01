import json, re, urllib.request, urllib.error
env = open("/etc/relay/relay.env", encoding="utf-8").read()
tok = re.search(r'set \$relay_token "([^"]+)"', env).group(1)
print("token length:", len(tok))
body = json.dumps({"model": "mimo-v2.6-flash",
                   "messages": [{"role": "user", "content": "reply with one word: ok"}],
                   "max_tokens": 8, "stream": False}).encode()
req = urllib.request.Request("http://8.153.153.176/v1/chat/completions", data=body, method="POST")
req.add_header("Authorization", "Bearer " + tok)
req.add_header("Content-Type", "application/json")
req.add_header("User-Agent", "curl/8.5.0")
try:
    r = urllib.request.urlopen(req, timeout=90)
    print("status:", r.status)
    for h in ("X-Relay-Rewritten-From", "X-Relay-Rewritten-To", "X-Relay-Rewritten-Policy"):
        print(h + ":", r.headers.get(h))
    d = json.loads(r.read().decode())
    print("body model:", d.get("model"))
    print("reply:", ((d.get("choices") or [{}])[0].get("message") or {}).get("content", "")[:60])
except urllib.error.HTTPError as e:
    print("HTTP", e.code)
    for h in ("X-Relay-Rewritten-From", "X-Relay-Rewritten-To", "X-Relay-Rewritten-Policy"):
        print(h + ":", e.headers.get(h))
    print(e.read().decode("utf-8", "replace")[:300])
