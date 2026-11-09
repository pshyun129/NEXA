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


def latest_output(outputs: dict, key: str) -> dict | None:
    """앞 노드 출력 중 key 값을 가진 가장 최근 출력 전체 (바인딩을 안 했을 때의 기본 동작)"""
    for out in reversed(list(outputs.values())):
        if isinstance(out, dict) and out.get(key) is not None:
            return out
    return None