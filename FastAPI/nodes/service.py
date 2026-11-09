from core.exceptions import BusinessException, ErrorCode
from nodes.registry import CATEGORIES, catalog, get_node_impl


def get_categories() -> list[dict]:
    return CATEGORIES


def search_nodes(category: str | None, q: str | None) -> list[dict]:
    result = catalog()

    if category:
        result = [n for n in result if n["category"] == category]

    if q:
        result = [n for n in result if q in n["name"] or q in n["description"]]

    return result


def get_node(node_type: str) -> dict:
    node = get_node_impl(node_type)
    if node is None:
        raise BusinessException(ErrorCode.NODE_NOT_FOUND)
    return node.to_catalog()