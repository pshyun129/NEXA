from datetime import datetime

from sqlalchemy import ForeignKey, String, Text, UniqueConstraint, func
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import Mapped, mapped_column, relationship

from core.database import Base


def empty_graph() -> dict:
    return {"nodes": [], "edges": []}


class Workflow(Base):
    __tablename__ = "workflow"

    id: Mapped[int] = mapped_column(primary_key=True)
    name: Mapped[str] = mapped_column(String(100))
    description: Mapped[str | None] = mapped_column(Text)
    draft_graph: Mapped[dict] = mapped_column(JSONB, default=empty_graph)
    input_form: Mapped[list] = mapped_column(JSONB, default=list)
    placements: Mapped[list] = mapped_column(JSONB, default=list)
    deployed_version: Mapped[int | None]
    created_by: Mapped[str | None] = mapped_column(String(50))
    created_at: Mapped[datetime] = mapped_column(server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(
        server_default=func.now(), onupdate=func.now()
    )

    versions: Mapped[list["WorkflowVersion"]] = relationship(
        back_populates="workflow",
        cascade="all, delete-orphan",
        order_by="WorkflowVersion.version",
    )


class WorkflowVersion(Base):
    __tablename__ = "workflow_version"
    __table_args__ = (UniqueConstraint("workflow_id", "version"),)

    id: Mapped[int] = mapped_column(primary_key=True)
    workflow_id: Mapped[int] = mapped_column(
        ForeignKey("workflow.id", ondelete="CASCADE")
    )
    version: Mapped[int]
    graph: Mapped[dict] = mapped_column(JSONB)
    created_at: Mapped[datetime] = mapped_column(server_default=func.now())

    workflow: Mapped["Workflow"] = relationship(back_populates="versions")