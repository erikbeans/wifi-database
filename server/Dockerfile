FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

COPY server/pyproject.toml server/alembic.ini ./
COPY server/app ./app
COPY server/alembic ./alembic
COPY docs/PRIVACY.md docs/TERMS.md ./

RUN pip install --no-cache-dir . \
    && useradd --create-home --shell /usr/sbin/nologin appuser \
    && chmod -R a+rX /app

USER appuser

EXPOSE 8000

CMD ["sh", "-c", "alembic upgrade head && exec uvicorn app.main:app --host 0.0.0.0 --port 8000"]
