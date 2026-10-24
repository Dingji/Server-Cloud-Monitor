import hashlib, re
def slug_key(text):
    h = hashlib.sha1(text.encode("utf-8")).hexdigest()[:10]
    prefix = re.sub(r"[^\w]+", "_", text[:24].strip().lower(), flags=re.UNICODE)
    prefix = re.sub(r"_+", "_", prefix).strip("_") or "s"
    return f"{prefix}_{h}"
for t in ["http://主机[:端口][/路径]", "URL（HTTP/HTTPS）", "{0} 读 · {1} 写", "失败"]:
    print(slug_key(t), t)
