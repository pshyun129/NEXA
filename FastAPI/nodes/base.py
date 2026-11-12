from dataclasses import dataclass, field


@dataclass
class NodeContext:
    run_id: int
    node_id: str
    config: dict
    inputs: dict
    outputs: dict


@dataclass
class NodeResult:
    output: dict = field(default_factory=dict)
    branch: str | None = None


class BaseNode:
    node_type: str
    name: str
    category: str
    description: str= ""
    config_fields: list[dict] = []
    branches: list[str] = []
    output_vars: list[dict] = []

    def run(self, ctx: NodeContext) -> NodeResult:
        raise NotImplementedError(f"{self.node_type}")

    def to_catalog(self) -> dict:
        return{
            "node_type": self.node_type,
            "name": self.name,
            "category": self.category,
            "description": self.description,
            "config_fields": self.config_fields,
            "branches": self.branches,
            "outputs": self.output_vars,
        }


class Progress:
    """오래 걸리는 노드(영수증 수백 장)의 진행 상황을 실행 단계 출력에 남김 → 화면이 /steps로 읽어 'n/전체장'을 보여 줌.
    너무 자주 쓰지 않게 1초에 한 번만 DB에 씀. 노드가 끝나면 finish()가 출력 전체를 덮어씀"""

    def __init__(self, ctx: "NodeContext", total: int, label: str):
        import threading
        self.ctx, self.total, self.label = ctx, total, label
        self.done = 0
        self._last = 0.0
        self._lock = threading.Lock()

    def tick(self, n: int = 1) -> None:
        import time
        with self._lock:
            self.done += n
            now = time.monotonic()
            if self.total < 2 or (now - self._last < 1.0 and self.done < self.total):
                return
            self._last = now
            done = self.done
        try:
            from sqlalchemy import update

            from core.database import SessionLocal
            from runs.models import RunStatus, RunStep
            with SessionLocal() as db:
                db.execute(update(RunStep)
                           .where(RunStep.run_id == self.ctx.run_id, RunStep.node_id == self.ctx.node_id,
                                  RunStep.status == RunStatus.RUNNING)
                           .values(output={"progress": {"done": done, "total": self.total, "label": self.label}}))
                db.commit()
        except Exception:      # 진행 표시는 부가 기능: 실패해도 노드는 계속
            pass


def latest_output(outputs: dict, key: str) -> dict | None:
    """앞 노드 출력 중 key 값을 가진 가장 최근 출력 전체 (바인딩을 안 했을 때의 기본 동작)"""
    for out in reversed(list(outputs.values())):
        if isinstance(out, dict) and out.get(key) is not None:
            return out
    return None