import logging

from langgraph.types import Command
from sqlalchemy import func, select, update

from core.metrics import RUNS
from core.database import SessionLocal
from engine.builder import StepRecorder, build_graph
from engine.checkpointer import get_checkpointer
from engine.tokens import clear_token, set_token
from events.approval_results import delete_results_for_run, resume_if_ready
from runs.models import Run, RunStatus, RunStep


log = logging.getLogger(__name__)


class RunCanceled(Exception):
    pass


class DbRecorder(StepRecorder):
    def __init__(self, run_id: int):
        self.run_id = run_id
        self.step_ids: dict[str, int] = {}

    
    def start(self, node_id: str, node_type: str) -> None:
        with SessionLocal() as db:
            run = db.get(Run, self.run_id)
            if run.status == RunStatus.CANCELED:
                raise RunCanceled()

            step = db.scalars(
                select(RunStep).where(
                    RunStep.run_id == self.run_id,
                    RunStep.node_id == node_id,
                    RunStep.status == RunStatus.WAITING,
                )
            ).first()
            if step is None:
                step = RunStep(run_id=self.run_id, node_id=node_id, node_type=node_type)
                db.add(step)
            else:
                step.status = RunStatus.RUNNING

            run.current_node_id = node_id
            db.commit()
            self.step_ids[node_id] = step.id


    def finish(self, node_id: str, output: dict, branch: str | None) -> None:
        with SessionLocal() as db:
            step = db.get(RunStep, self.step_ids[node_id])
            step.status = RunStatus.SUCCEEDED
            step.output = output
            step.branch = branch
            step.finished_at = func.now()
            db.commit()

    
    def fail(self, node_id: str, error: Exception) -> None:
        with SessionLocal() as db:
            step = db.get(RunStep, self.step_ids[node_id])
            step.status = RunStatus.FAILED
            step.error = str(error)
            step.finished_at = func.now()
            db.commit()


    def wait(self, node_id: str) -> None:
        with SessionLocal() as db:
            step = db.get(RunStep, self.step_ids[node_id])
            step.status = RunStatus.WAITING
            db.commit()


def execute_run(run_id: int, token: str | None = None) -> None:
    with SessionLocal() as db:
        run = db.get(Run, run_id)
        if run is None or run.status != RunStatus.QUEUED:
            return
        graph_json = run.graph
        inputs = run.inputs
        thread_id = run.thread_id
        run.status = RunStatus.RUNNING
        run.started_at = func.now()
        db.commit()

    set_token(run_id, token)   # 이 실행 구간 동안 노드가 팀원 API 호출에 사용
    first_state = {"run_id": run_id, "inputs": inputs, "outputs": {}, "branches": {}}
    _run_graph(run_id, graph_json, thread_id, first_state)


def resume_run(run_id: int, decision: dict, token: str | None = None) -> None:
    with SessionLocal() as db:
        # WAITING → RUNNING 전환을 한 문장으로 처리: 동시에 두 번 재개돼도 한쪽만 성공
        # (중복 이벤트, 재개 버튼 두 번 클릭, 이벤트와 재확인이 겹치는 경우)
        claimed = db.execute(
            update(Run)
            .where(Run.id == run_id, Run.status == RunStatus.WAITING)
            .values(status=RunStatus.RUNNING)
        ).rowcount
        if claimed != 1:
            return
        run = db.get(Run, run_id)
        graph_json = run.graph
        thread_id = run.thread_id
        db.commit()

    set_token(run_id, token)   # 처음 토큰은 만료됐을 수 있으니 재개 요청의 토큰으로 교체
    _run_graph(run_id, graph_json, thread_id, Command(resume=decision))


def _run_graph(run_id: int, graph_json: dict, thread_id: str, graph_input) -> None:
    config = {"configurable": {"thread_id": thread_id}}
    try:
        graph = build_graph(graph_json, DbRecorder(run_id), checkpointer=get_checkpointer())
        result = graph.invoke(graph_input, config)
    except RunCanceled:
        return
    except Exception as e:
        log.exception("실행 실패 run_id=%s", run_id)
        _finish(run_id, RunStatus.FAILED, error=str(e))
        return
    finally:
        # 멈추든(WAITING) 끝나든 이 구간이 끝나면 토큰은 바로 지움 (몇 시간씩 들고 있지 않음)
        clear_token(run_id)

    if "__interrupt__" in result:
        # interrupt()에 넘긴 값 = 사람이 확인해야 할 내용
        payload = result["__interrupt__"][0].value
        _set_waiting(run_id, payload)
        # 결재 대기: 멈추는 사이에 결과가 이미 도착했을 수 있음 → 한 번 더 확인
        # (노드가 보관함을 본 직후, WAITING이 되기 직전에 이벤트가 온 경우)
        if isinstance(payload, dict) and payload.get("type") == "approval_wait":
            resume_if_ready(payload["request_key"])
        return

    _finish(run_id, RunStatus.SUCCEEDED, outputs=result["outputs"])


def _set_waiting(run_id: int, payload: dict | None = None) -> None:
    with SessionLocal() as db:
        run = db.get(Run, run_id)
        if run.status == RunStatus.CANCELED:
            return
        run.status = RunStatus.WAITING
        # 대기 중인 단계에 "무엇을 확인해야 하는지" 저장 → 화면이 /steps로 읽어서 보여 줌
        step = db.scalars(
            select(RunStep).where(RunStep.run_id == run_id, RunStep.status == RunStatus.WAITING)
        ).first()
        if step is not None:
            step.output = {"waiting_for": payload}
        db.commit()


def _finish(run_id: int, status: RunStatus, outputs: dict | None = None, error: str | None = None) -> None:
    RUNS.labels(status.value).inc()
    with SessionLocal() as db:
        run = db.get(Run, run_id)
        if run.status == RunStatus.CANCELED:
            return
        run.status = status
        run.outputs = outputs
        run.error = error
        run.finished_at = func.now()
        db.commit()
    # 끝난 실행의 결재 결과는 더 쓸 일이 없음 → 보관함 정리
    delete_results_for_run(run_id)