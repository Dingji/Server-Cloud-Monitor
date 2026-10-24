"""SMTP sending (163/QQ/Gmail compatible) with an email log."""
import html
import smtplib
import ssl
from email.message import EmailMessage

from . import db


def _svg(inner: str) -> str:
    return (
        '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#4f46e5" '
        'stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" '
        'style="vertical-align:-3px;margin-right:6px" aria-hidden="true">' + inner + "</svg>"
    )


# hand-drawn inline icons for mail bodies
MAIL_ICONS = {
    "check": _svg('<circle cx="12" cy="12" r="8.7"/><path d="M8.1 12.3l2.7 2.7 5.1-5.6"/>'),
    "calendar": _svg('<rect x="3.4" y="5.4" width="17.2" height="15.2" rx="2.6"/><path d="M3.4 10h17.2M8 3.4v4M16 3.4v4"/>'),
    "bell": _svg('<path d="M12 3.6a5.4 5.4 0 0 0-5.4 5.4c0 3.9-1.5 5.3-1.5 5.3h13.8s-1.5-1.4-1.5-5.3A5.4 5.4 0 0 0 12 3.6z"/><path d="M10.3 17.8a1.9 1.9 0 0 0 3.4 0"/>'),
    "warn": _svg('<path d="M12 4.3 20.7 19.4H3.3z"/><path d="M12 9.7v3.9"/><circle cx="12" cy="16.9" r="1" fill="#d97706" stroke="none"/>'),
}


def smtp_ready(cfg: dict | None = None) -> bool:
    cfg = cfg if cfg is not None else db.all_settings()
    return bool(cfg.get("smtp_host") and cfg.get("smtp_user") and cfg.get("smtp_pass") and (cfg.get("mail_to") or cfg.get("smtp_user")))


def _log(kind: str, subject: str, status: str, error: str = "") -> None:
    db.execute(
        "INSERT INTO email_log(sent_at,kind,subject,status,error) VALUES(?,?,?,?,?)",
        (db.now(), kind, subject, status, error[:2000]),
    )


def send(subject: str, html: str, kind: str = "test", to_override: str | None = None,
         attachments: list[tuple] | None = None) -> tuple[bool, str]:
    """Send one HTML mail. Returns (ok, error_message). Always logs the attempt.

    to_override: recipient address (comma-separated allowed) — used for
    per-user deliveries (verification, shares, reminders). Falls back to the
    configured global recipient.
    """
    cfg = db.all_settings()
    if not smtp_ready(cfg):
        err = "SMTP 未配置完整（需要主机/端口/账号/授权码/收件人）"
        _log(kind, subject, "skipped", err)
        return False, err

    host = cfg.get("smtp_host", "")
    port = int(cfg.get("smtp_port") or 465)
    use_ssl = cfg.get("smtp_ssl", "1") == "1"
    user = cfg.get("smtp_user", "")
    password = cfg.get("smtp_pass", "")
    sender = cfg.get("mail_from") or user
    target = str(to_override or cfg.get("mail_to") or user)
    to_list = [x.strip() for x in target.replace(";", ",").split(",") if x.strip()]

    msg = EmailMessage()
    msg["Subject"] = subject
    msg["From"] = f"NoteBox <{sender}>"
    msg["To"] = ", ".join(to_list)
    plain = (
        "这是一封来自 NoteBox 的 HTML 邮件，如果看不到内容，请开启 HTML 显示。\n"
        "If you cannot view the HTML content, please enable HTML display.\n\n"
        + subject
    )
    msg.set_content(plain)
    msg.add_alternative(html, subtype="html")
    for att in attachments or []:
        filename, data, maintype, subtype = att
        msg.add_attachment(data, maintype=maintype, subtype=subtype, filename=filename)

    try:
        context = ssl.create_default_context()
        if use_ssl:
            with smtplib.SMTP_SSL(host, port, timeout=25, context=context) as s:
                if user:
                    s.login(user, password)
                s.send_message(msg)
        else:
            with smtplib.SMTP(host, port, timeout=25) as s:
                s.ehlo()
                if s.has_extn("starttls"):
                    s.starttls(context=context)
                    s.ehlo()
                if user:
                    s.login(user, password)
                s.send_message(msg)
        _log(kind, subject, "ok")
        return True, ""
    except Exception as exc:  # noqa: BLE001 - surface any SMTP failure to the UI
        _log(kind, subject, "error", f"{type(exc).__name__}: {exc}")
        return False, f"{type(exc).__name__}: {exc}"


def render_mail(title: str, lines: list[str], button: str | None = None, base_url: str = "",
                icon: str = "check", button_text: str = "打开 NoteBox", extra_html: str = "") -> str:
    """Small inline-styled HTML mail body (works without external assets)."""
    items = "".join(f"<tr><td style='padding:6px 0;border-bottom:1px solid #eee'>{ln}</td></tr>" for ln in lines)
    ico = MAIL_ICONS.get(icon, MAIL_ICONS["check"])
    safe_title = html.escape(str(title))
    btn = ""
    if button and base_url:
        btn = (
            f"<p style='margin-top:18px'><a href='{base_url}' style='background:#4f46e5;color:#fff;"
            f"padding:10px 18px;border-radius:8px;text-decoration:none;display:inline-block'>{button_text}</a></p>"
        )
    btn += extra_html
    return f"""<div style="font-family:-apple-system,'PingFang SC','Microsoft YaHei',sans-serif;max-width:560px;margin:0 auto;color:#1f2937">
  <div style="background:#4f46e5;color:#fff;padding:16px 20px;border-radius:10px 10px 0 0">
    <div style="font-size:18px;font-weight:700">NoteBox</div>
  </div>
  <div style="border:1px solid #e5e7eb;border-top:0;padding:18px 20px;border-radius:0 0 10px 10px">
    <h2 style="margin:0 0 12px;font-size:16px">{ico}{safe_title}</h2>
    <table style="width:100%;border-collapse:collapse;font-size:14px">{items}</table>
    {btn}
    <p style="color:#9ca3af;font-size:12px;margin-top:20px">此邮件由 NoteBox 自动发送。</p>
  </div>
</div>"""
