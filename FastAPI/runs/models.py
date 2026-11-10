import uuid
from datetime import datetime
from enum import Enum

from sqlalchemy import BigInteger, Boolean, ForeignKey, String, Text, func
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import Mapped, mapped_column, relationship

from core.database import Base


class RunStatus(str, Enum):
    QUEUED = "QUEUED"
    RUNNING = "RUNNING"
    WAITING = "WAITING"
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"
    CANCELED = "CANCELED"


FINISHED_STATUSES = {RunStatus.SUCCEEDED, RunStatus.FAILED, RunStatus.CANCELED}


def new_thread_id() -> str:
    return uuid.uuid4().hex


class Run(Base):
    # 테이블 기본 정보
    __tablename__ = "run"
    id: Mapped[int] = mapped_column(primary_key=True)
    workflow_id: Mapped[int] = mapped_column(ForeignKey("workflow.id"))
    version: Mapped[int | None]
    is_test: Mapped[bool] = mapped_column(Boolean, default=False)
    graph: Mapped[dict] = mapped_column(JSONB)
    inputs: Mapped[dict] = mapped_column(JSONB, default=dict)

    # Run 테이블 진행상태
    status: Mapped[str] = mapped_column(String(20), default=RunStatus.QUEUED)
    thread_id: Mapped[str] = mapped_column(String(64), unique=True, default=new_thread_id)
    current_node_id: Mapped[str | None] = mapped_column(String(50))
    outputs: Mapped[dict | None] = mapped_column(JSONB)
    error: Mapped[str | None] = mapped_column(Text)

    # Run 테이블 관계 관련
    requested_by: Mapped[str | None] = mapped_column(String(50))
    created_at: Mapped[datetime] = mapped_column(server_default=func.now())
    started_at: Mapped[datetime | None]
    finished_at: Mapped[datetime | None]

    steps: Mapped[list["RunStep"]] = relationship(
        back_populates="run",
        cascade="all, delete-orphan",
        order_by="RunStep.id",
    )

    @property
    def waiting_type(self) -> str | None:
        """멈춘 단계가 기다리는 것: human_confirm(사람 확인) / approval_wait(결재 대기). WAITING이 아니면 None"""
        if self.status != RunStatus.WAITING:
            return None
        for step in reversed(self.steps):
            if step.status == RunStatus.WAITING:
                return ((step.output or {}).get("waiting_for") or {}).get("type")
        return None


class RunStep(Base):
    __tablename__ = "run_step"

    id: Mapped[int] = mapped_column(primary_key=True)
    run_id: Mapped[int] = mapped_column(ForeignKey("run.id", ondelete="CASCADE"))
    node_id: Mapped[str] = mapped_column(String(50))
    node_type: Mapped[str] = mapped_column(String(50))
    status: Mapped[str] = mapped_column(String(20), default=RunStatus.RUNNING)
    branch: Mapped[str | None] = mapped_column(String(50))
    output: Mapped[dict | None] = mapped_column(JSONB)
    error: Mapped[str | None] = mapped_column(Text)
    started_at: Mapped[datetime] = mapped_column(server_default=func.now())
    finished_at: Mapped[datetime | None]

    run: Mapped["Run"] = relationship(back_populates="steps")


class RunFile(Base):
    __tablename__ = "run_file"
    id: Mapped[int] = mapped_column(primary_key=True)
    run_id: Mapped[int | None] = mapped_column(ForeignKey("run.id"))
    original_name: Mapped[str] = mapped_column(String(255))
    stored_name: Mapped[str] = mapped_column(String(64), unique=True)
    content_type: Mapped[str | None] = mapped_column(String(100))
    size: Mapped[int]
    uploaded_by: Mapped[str | None] = mapped_column(String(50))
    created_at: Mapped[datetime] = mapped_column(server_default=func.now())


class RunFeedback(Base):
    """AI 추출 결과의 확정 기록 (수정 전/후). MLOps 평가·재학습 데이터로 사용."""
    __tablename__ = "run_feedback"

    id: Mapped[int] = mapped_column(primary_key=True)
    run_id: Mapped[int] = mapped_column(ForeignKey("run.id", ondelete="CASCADE"))
    node_id: Mapped[str] = mapped_column(String(50))
    doc_type: Mapped[str | None] = mapped_column(String(50))          # 문서 종류 (영수증 등)
    model_version: Mapped[str | None] = mapped_column(String(100))    # 어느 모델 버전이 추출했는지
    model_output: Mapped[dict] = mapped_column(JSONB)                 # AI가 낸 값
    final_output: Mapped[dict] = mapped_column(JSONB)                 # 확정된 값 (= 라벨)
    edited_fields: Mapped[list] = mapped_column(JSONB)                # 바뀐 필드 목록
    label_source: Mapped[str] = mapped_column(String(20))             # human: 사람 확정 / llm_agree: 로컬·GMS 일치로 자동 확정
    created_at: Mapped[datetime] = mapped_column(server_default=func.now())


class ApprovalResult(Base):
    """결재 결과 보관함 (Kafka approval.document.result 중 AUTOMATION). requestKey당 1건.

    이벤트가 대기 노드보다 먼저 도착해도 잃지 않도록 여기에 먼저 저장한다.
    """
    __tablename__ = "approval_result"

    request_key: Mapped[str] = mapped_column(String(36), primary_key=True)
    status: Mapped[str] = mapped_column(String(20))                  # APPROVED / REJECTED / CANCEL
    document_id: Mapped[int | None] = mapped_column(BigInteger)      # 결재 서비스 문서 ID (Long)
    doc_no: Mapped[str | None] = mapped_column(String(50))
    event_id: Mapped[str | None] = mapped_column(String(36))         # 추적용
    received_at: Mapped[datetime] = mapped_column(server_default=func.now())