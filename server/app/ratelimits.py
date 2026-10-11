from slowapi import Limiter
from slowapi.util import get_remote_address

from app.config import get_settings


def user_key(request) -> str:
    user_id = getattr(request.state, "user_id", None)
    if user_id is not None:
        return f"user:{user_id}"
    return f"ip:{get_remote_address(request)}"


settings = get_settings()
default_limits = [f"{settings.rate_limit_per_minute}/minute"] if settings.rate_limit_per_minute > 0 else []
limiter = Limiter(key_func=get_remote_address, default_limits=default_limits)
