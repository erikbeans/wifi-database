"""Periodic cleanup of passwords whose persistent grace period has elapsed."""

import asyncio
import logging

from app import audit
from app.access_points import pruning
from app.database import SessionLocal

logger = logging.getLogger(__name__)
CLEANUP_INTERVAL_SECONDS = 60 * 60
BATCH_SIZE = 100


def run_cleanup() -> None:
    while True:
        with SessionLocal.begin() as db:
            processed = pruning.cleanup_due(db, batch_size=BATCH_SIZE)
            audit.purge(db)
        if processed < BATCH_SIZE:
            return


async def cleanup_loop() -> None:
    # Run immediately at startup to catch deadlines that passed during downtime.
    while True:
        try:
            await asyncio.to_thread(run_cleanup)
        except Exception:
            logger.exception("Password cleanup failed; retrying on the next interval")
        await asyncio.sleep(CLEANUP_INTERVAL_SECONDS)
