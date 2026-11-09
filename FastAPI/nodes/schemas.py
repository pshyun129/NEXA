from core.schemas import CamelModel

class CategoryResponse(CamelModel):
    code: str
    name: str


class NodeSummary(CamelModel):
    node_type: str
    name: str
    category: str
    description: str


class ConfigField(CamelModel):
    key: str
    label: str
    type: str
    required: bool = False
    options: list[str] = []


class OutputVar(CamelModel):
    key: str
    label: str
    type: str


class NodeDetail(NodeSummary):
    config_fields: list[ConfigField] = []
    branches: list[str] = []
    outputs: list[OutputVar] = []


