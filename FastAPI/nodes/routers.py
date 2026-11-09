from fastapi import APIRouter

from core.response import ApiResult, ok
from nodes import service
from nodes.schemas import CategoryResponse, NodeSummary, NodeDetail

router = APIRouter(prefix="/nodes", tags=["노드"])


@router.get("/categories", response_model=ApiResult[list[CategoryResponse]])
def get_categories():
    return ok(service.get_categories())


@router.get("", response_model=ApiResult[list[NodeSummary]])
def search_nodes(category: str | None = None, q: str | None = None):
    return ok(service.search_nodes(category, q))


@router.get("/{node_type}", response_model=ApiResult[NodeDetail])
def get_node(node_type: str):
    return ok(service.get_node(node_type))


