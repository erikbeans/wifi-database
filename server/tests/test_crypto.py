import base64

import pytest
from cryptography.exceptions import InvalidTag

from app.access_points import crypto


def test_seal_open_round_trip(monkeypatch):
    blob, version = crypto.seal("espresso42")
    assert version == 1
    assert crypto.open(blob) == "espresso42"


def test_fresh_nonce_per_seal():
    first_blob, _ = crypto.seal("same-password")
    second_blob, _ = crypto.seal("same-password")
    assert first_blob != second_blob
    assert first_blob[: crypto.NONCE_BYTES] != second_blob[: crypto.NONCE_BYTES]


def test_ciphertext_is_not_plaintext():
    blob, _ = crypto.seal("supersecret")
    assert b"supersecret" not in blob


def test_open_with_wrong_key_fails(monkeypatch):
    blob, _ = crypto.seal("supersecret")
    wrong = base64.b64decode(base64.b64encode(b"1" * 32))
    monkeypatch.setattr(crypto, "_key", lambda: wrong)
    with pytest.raises(ValueError):
        crypto.open(blob)


def test_seal_without_configured_key_fails(monkeypatch):
    monkeypatch.setattr(crypto, "_key", lambda: (_ for _ in ()).throw(RuntimeError("WIFI_ENCRYPTION_KEY is not configured")))
    with pytest.raises(RuntimeError):
        crypto.seal("anything")


def test_open_tampered_ciphertext_fails():
    blob, _ = crypto.seal("supersecret")
    tampered = blob[:-1] + bytes([blob[-1] ^ 0xFF])
    try:
        crypto.open(tampered)
        raise AssertionError("expected failure")
    except Exception as exc:
        assert isinstance(exc, (InvalidTag, ValueError))
