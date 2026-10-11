import base64
import os
from functools import lru_cache

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from app.config import get_settings

NONCE_BYTES = 12
KEY_VERSION = 1


@lru_cache
def _key() -> bytes:
    raw = get_settings().encryption_key
    if not raw:
        raise RuntimeError("WIFI_ENCRYPTION_KEY is not configured")
    key = base64.b64decode(raw)
    if len(key) != 32:
        raise RuntimeError("WIFI_ENCRYPTION_KEY must be the base64 of exactly 32 bytes")
    return key


def seal(plaintext: str) -> tuple[bytes, int]:
    nonce = os.urandom(NONCE_BYTES)
    blob = nonce + AESGCM(_key()).encrypt(nonce, plaintext.encode("utf-8"), None)
    return blob, KEY_VERSION


def open(ciphertext: bytes) -> str:
    nonce, body = ciphertext[:NONCE_BYTES], ciphertext[NONCE_BYTES:]
    try:
        return AESGCM(_key()).decrypt(nonce, body, None).decode("utf-8")
    except InvalidTag as exc:
        raise ValueError("ciphertext failed authentication") from exc
