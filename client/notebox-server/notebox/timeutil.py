"""Timezone helper: ZoneInfo with a safe fixed-offset fallback."""
from datetime import timedelta, timezone
from zoneinfo import ZoneInfo

# Fixed offsets used only when the tz database is unavailable (e.g. bare Windows).
_FIXED = {
    "Asia/Shanghai": 8,
    "Asia/Hong_Kong": 8,
    "Asia/Chongqing": 8,
    "Asia/Taipei": 8,
    "Asia/Macau": 8,
    "Asia/Tokyo": 9,
    "Asia/Seoul": 9,
    "Asia/Singapore": 8,
    "UTC": 0,
}


def get_tz(key: str | None = None):
    key = key or "Asia/Shanghai"
    try:
        return ZoneInfo(key)
    except Exception:
        hours = _FIXED.get(key, 0)
        return timezone(timedelta(hours=hours), key)
