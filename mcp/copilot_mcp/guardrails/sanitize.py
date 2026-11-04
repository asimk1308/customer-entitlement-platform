"""Prepares text from source systems for a model. Case subjects and comments can be written by
customers, so this text is attacker-controllable. This reduces risk but doesn't remove it. The real
protection against injection is that a fooled model still can't approve its own writes."""
import re
import unicodedata

EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
PHONE = re.compile(r"\+?\(?\d[\d\s().-]{7,}\d")
MONEY = re.compile(r"(?:[$€£]\s?\d[\d,.]*\s?[kKmM]?\b)|(?:\b\d[\d,.]*\s?(?:USD|EUR|GBP)\b)")
# A signal for people and logs, not a defence. Trivial rewording gets past any list like this.
INJECTION_PATTERNS = re.compile(
    r"ignore (?:all |any |the )?(?:previous|prior|above|earlier)"
    r"|disregard (?:all |any |the )?(?:previous|prior|above|earlier|your)"
    r"|forget (?:all |your )?(?:previous|prior|instructions)"
    r"|(?:system|developer) (?:prompt|message)"
    r"|you are now"
    r"|new instructions"
    r"|escalate (?:all|every)"
    r"|approval[_ ]?id"
    r"|</?(?:system|instructions?|assistant|tool)\b",
    re.IGNORECASE,
)


def _is_phone(match: re.Match) -> bool:
    text = match.group()
    digits = sum(char.isdigit() for char in text)
    # Avoids redacting dates and IDs, which also look like runs of digits and separators.
    return digits >= 10 or (text.startswith("+") and digits >= 8)


def strip_invisible(text: str) -> str:
    """Removes control and format characters, including zero-width ones that can hide instructions
    from a reviewer or split up words to avoid detection."""
    kept = (
        " " if char in "\n\r\t" else char
        for char in unicodedata.normalize("NFKC", text)
        if char in "\n\r\t" or unicodedata.category(char) not in ("Cc", "Cf", "Co", "Cn")
    )
    return " ".join("".join(kept).split())


def redact(text: str) -> str:
    text = EMAIL.sub("[email]", text)
    text = PHONE.sub(lambda m: "[phone]" if _is_phone(m) else m.group(), text)
    return MONEY.sub("[amount]", text)


def clean(text: str, max_length: int) -> str:
    cleaned = redact(strip_invisible(text))
    return cleaned if len(cleaned) <= max_length else cleaned[: max_length - 1] + "…"


def looks_like_injection(text: str) -> bool:
    return bool(INJECTION_PATTERNS.search(strip_invisible(text)))
