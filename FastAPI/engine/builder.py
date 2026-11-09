from typing import Annotated, TypedDict

import time

from langgraph.errors import GraphInterrupt
from langgraph.graph import END, START, StateGraph

from core.metrics import NODE_BRANCH, NODE_SECONDS
from nodes.base import NodeContext
from nodes.registry import get_node_impl
from engine.binding import resolve


def merge_dict(left: dict, right: dict) -> dict:
    return {**left, **right}


class RunState(TypedDict):
    run_id: int
    inputs: dict
    outputs: Annotated[dict, merge_dict]
    branches: Annotated[dict, merge_dict]


class StepRecorder:
    def start(self, node_id: str, node_type: str) -> None:
        pass

    def finish(self, node_id: str, output: dict, branch: str | None) -> None:
        pass

    def fail(self, node_id: str, error: Exception) -> None:
        pass

    def wait(self, node_id: str) -> None:
        pass


def _make_node_fn(node_id: str, node_type: str, config: dict, recorder: StepRecorder):
    node = get_node_impl(node_type)
    if node is None:
        raise ValueError(f"알 수 없는 노드 종류입니다: {node_type}")

    def node_fn(state: RunState) -> dict:
        recorder.start(node_id, node_type)
        ctx = NodeContext(
            run_id=state["run_id"],
            node_id=node_id,
            config=resolve(config, state["outputs"]),  # {{n1.text}} 같은 참조를 지금까지의 결과로 치환
            inputs=state["inputs"],
            outputs=state["outputs"],
        )
        start = time.perf_counter()
        try:
            result = node.run(ctx)
        except GraphInterrupt:
            NODE_SECONDS.labels(node_type, "wait").observe(time.perf_counter() - start)
            recorder.wait(node_id)
            raise
        except Exception as e:
            NODE_SECONDS.labels(node_type, "fail").observe(time.perf_counter() - start)
            recorder.fail(node_id, e)
            raise
        NODE_SECONDS.labels(node_type, "ok").observe(time.perf_counter() - start)
        if result.branch:
            NODE_BRANCH.labels(node_type, result.branch).inc()
        recorder.finish(node_id, result.output, result.branch)
        return {
            "outputs": {node_id: result.output},
            "branches": {node_id: result.branch},
        }

    return node_fn


def _make_router(node_id: str, branch_targets: dict[str, str]):
    def route(state: RunState) -> str:
        branch = state["branches"].get(node_id)
        return branch_targets.get(branch, END)

    return route


def build_graph(graph_json: dict, recorder: StepRecorder | None = None, checkpointer=None):
    recorder = recorder or StepRecorder()
    nodes = graph_json["nodes"]
    edges = graph_json["edges"]

    builder = StateGraph(RunState)

    for n in nodes:
        builder.add_node(n["id"], _make_node_fn(n["id"], n["type"], n.get("config", {}), recorder))

    outgoing: dict[str, list[dict]] = {n["id"]: [] for n in nodes}
    has_incoming: set[str] = set()
    for e in edges:
        outgoing[e["source"]].append(e)
        has_incoming.add(e["target"])

    for n in nodes:
        node_id = n["id"]
        if get_node_impl(n["type"]).branches:
            branch_targets = {e["branch"]: e["target"] for e in outgoing[node_id]}
            builder.add_conditional_edges(node_id, _make_router(node_id, branch_targets))
        elif outgoing[node_id]:
            builder.add_edge(node_id, outgoing[node_id][0]["target"])
        else:
            builder.add_edge(node_id, END)

    start_ids = [n["id"] for n in nodes if n["id"] not in has_incoming]
    builder.add_edge(START, start_ids[0])

    return builder.compile(checkpointer=checkpointer)
    