#!/usr/bin/env python3
"""SMTP sender for the panel's ops notification channels.

Reads one JSON object on stdin and sends mail via smtplib (the box has python3 +
smtplib but no MTA, so node shells out to this). Prints a one-line status; exit
code 0 means delivered.

  {"host":"smtp.qq.com","port":465,"security":"ssl","user":"a@b.com","password":"…",
   "from":"Cloud Monitor <a@b.com>","to":["ops@x.com"],"cc":[],
   "subject":"[告警] CPU 90%","text":"…"}
"""
import json
import smtplib
import ssl
import sys
from email.message import EmailMessage


def main():
    try:
        cfg = json.load(sys.stdin)
    except Exception as e:
        print("bad input: %s" % e)
        return 2

    to = [a for a in (cfg.get("to") or []) if a]
    cc = [a for a in (cfg.get("cc") or []) if a]
    if not to:
        print("no recipients")
        return 2
    if not cfg.get("host"):
        print("no smtp host")
        return 2

    msg = EmailMessage()
    msg["Subject"] = cfg.get("subject") or "Cloud Monitor"
    msg["From"] = cfg.get("from") or cfg.get("user") or ""
    msg["To"] = ", ".join(to)
    if cc:
        msg["Cc"] = ", ".join(cc)
    msg.set_content(cfg.get("text") or "")

    sec = (cfg.get("security") or "ssl").lower()
    user = cfg.get("user") or ""
    password = cfg.get("password") or ""
    timeout = int(cfg.get("timeout") or 20)

    try:
        if sec == "starttls":
            with smtplib.SMTP(cfg["host"], int(cfg.get("port") or 587), timeout=timeout) as s:
                s.ehlo()
                s.starttls(context=ssl.create_default_context())
                s.ehlo()
                if user:
                    s.login(user, password)
                s.send_message(msg, from_addr=msg["From"], to_addrs=to + cc)
        else:
            with smtplib.SMTP_SSL(cfg["host"], int(cfg.get("port") or 465),
                                  timeout=timeout, context=ssl.create_default_context()) as s:
                if user:
                    s.login(user, password)
                s.send_message(msg, from_addr=msg["From"], to_addrs=to + cc)
    except Exception as e:
        print("send failed: %s: %s" % (type(e).__name__, e))
        return 1

    print("sent to %s" % ", ".join(to + cc))
    return 0


if __name__ == "__main__":
    sys.exit(main())
