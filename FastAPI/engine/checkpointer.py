from langgraph.checkpoint.postgres import PostgresSaver
from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool

from core.config import settings

_pool: ConnectionPool | None = None
_saver: PostgresSaver | None = None


def init_checkpointer() -> None:
    global _pool, _saver

    _pool = ConnectionPool(
        conninfo=settings.checkpoint_url,
        max_size=10,
        kwargs={"autocommit": True, "prepare_threshold": 0, "row_factory": dict_row},
        open=True,
    )

    _saver = PostgresSaver(_pool)
    _saver.setup()


def get_checkpointer() -> PostgresSaver:
    if _saver is None:
        raise RuntimeError("checkpointer가 준비되지 않았습니다.")
    return _saver


def close_checkpointer() -> None:
    if _pool is not None:
        _pool.close()

