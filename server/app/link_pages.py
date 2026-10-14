import html
from functools import lru_cache
from pathlib import Path

import markdown
from fastapi import HTTPException
from fastapi.responses import HTMLResponse, JSONResponse

from app.config import get_settings

_POLICY_BASE = Path(__file__).resolve().parent.parent

_PAGE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>WiFi Database</title>
<style>
 body { font-family: system-ui, sans-serif; display: flex; justify-content: center; background: #f5f5f7; margin: 0; padding: 2rem; }
 .card { background: #ffffff; border-radius: 12px; padding: 2rem; max-width: 26rem; text-align: center; box-shadow: 0 1px 4px rgba(0,0,0,.12); }
 h2 { margin: 0 0 .5rem; }
 a.button { display: inline-block; margin-top: 1rem; padding: .8rem 1.4rem; background: #2563eb; color: #ffffff; border-radius: 8px; text-decoration: none; font-weight: 600; }
 p.small { color: #666666; font-size: .9rem; }
</style>
</head>
<body>
<div class="card">
 <h2>WiFi Database</h2>
 <p>Open this link in the WiFi Database app on the Android device it was sent to.</p>
 <a class="button" href="{app_link}">Open in the app</a>
 <p class="small">Nothing happened? The link expires after 24 hours and works once &mdash;
 request a fresh email from the app if needed.</p>
</div>
</body>
</html>"""


def _page(app_link: str) -> HTMLResponse:
    return HTMLResponse(_PAGE.replace("{app_link}", html.escape(app_link, quote=True)))


def verify_page(token: str) -> HTMLResponse:
    safe = html.escape(token, quote=True)
    return _page(f"wifidatabase://verify?token={safe}")


def password_reset_page(token: str) -> HTMLResponse:
    safe = html.escape(token, quote=True)
    return _page(f"wifidatabase://password-reset?token={safe}")


def assetlinks_response() -> JSONResponse:
    settings = get_settings()
    fingerprints = settings.android_sha256_fingerprints
    if not fingerprints:
        raise HTTPException(status_code=404, detail="not found")
    return JSONResponse(
        [
            {
                "relation": ["delegate_permission/common.handle_all_urls"],
                "target": {
                    "namespace": "android_app",
                    "package_name": settings.android_package,
                    "sha256_cert_fingerprints": fingerprints,
                },
            }
        ]
    )


_POLICY_SHELL = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>WiFi Database</title>
<style>
 body { font-family: system-ui, sans-serif; background: #f5f5f7; margin: 0; padding: 2rem 1rem; display: flex; justify-content: center; }
 .card { background: #ffffff; border-radius: 12px; padding: 2rem; max-width: 44rem; box-shadow: 0 1px 4px rgba(0,0,0,.12); line-height: 1.6; }
 h1 { font-size: 1.4rem; }
 h2 { font-size: 1.1rem; margin-top: 1.5rem; }
 a { color: #2563eb; }
 code { background: #f0f0f2; padding: .1rem .3rem; border-radius: 4px; }
</style>
</head>
<body>
<div class="card">
{body}
</div>
</body>
</html>"""


def _find_doc(name: str) -> Path:
    for candidate in (
        _POLICY_BASE / name,
        _POLICY_BASE.parent / "docs" / name,
    ):
        if candidate.exists():
            return candidate
    raise HTTPException(status_code=404, detail="not found")


@lru_cache(maxsize=4)
def _rendered_doc(name: str) -> str:
    return markdown.markdown(_find_doc(name).read_text(encoding="utf-8"))


def _policy_page(name: str) -> HTMLResponse:
    return HTMLResponse(_POLICY_SHELL.replace("{body}", _rendered_doc(name)))


def privacy_page() -> HTMLResponse:
    return _policy_page("PRIVACY.md")


def terms_page() -> HTMLResponse:
    return _policy_page("TERMS.md")
