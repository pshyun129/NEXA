"""매일 밤 자동 MLOps (GPU 서버). night_job.sh가 단계별로 부른다 — vLLM이 켜져 있어야 하는 단계와 꺼야 하는 학습 사이.

  python -X utf8 -m mlops.nightly prepare   # 데이터: 합성 데이터셋 받기 + 골든셋(실제 영수증) 받아 학습용/평가용 고정 분할
  python -X utf8 -m mlops.nightly dspy      # DSPy: 영수증 추출·규정 판정 프롬프트 최적화 → 자동 게이트 → 통과하면 champion
  python -X utf8 -m mlops.nightly sft --out ~/ai/sft/nightly.jsonl   # QLoRA 학습 데이터 (지금 champion 프롬프트 형식 + 골든 학습용)
  (night_job.sh: vLLM 내림 → QLoRA 학습 → champion 어댑터 + 새 어댑터를 붙여 vLLM 다시 올림)
  python -X utf8 -m mlops.nightly adapter --name receipt-qlora-YYYYMMDD --path ~/ai/adapters/...   # 새 어댑터 게이트
  python -X utf8 -m mlops.nightly summary   # 오늘 밤 결과 요약

새 데이터가 있을 때만 학습: 단계마다 '무엇으로 학습했는지'(데이터 해시 + 지금 champion)를 키로 MLflow 실험 nightly에 남기고,
  키가 지난번과 같으면 건너뜀 (같은 데이터로 매일 다시 최적화·게이트를 돌리면 test에 운으로 맞춰진 후보가 언젠가 통과함)
  강제로 다시: FORCE=1

승격 = MLflow Model Registry 별칭 champion 이동 → EC2 서비스(ai/champions.py)가 30분 안에 재시작 없이 반영
       어댑터는 다음 날 아침 start_day.sh가 champion 어댑터를 붙여 vLLM을 띄우면 반영
자동 롤백 = EC2 서비스가 승격 뒤 사람 수정률을 보고 되돌림 (ai/champions.py)
"""
import argparse
import json
import os
import tempfile
import time
from pathlib import Path

import yaml

from ai.champions import ADAPTER, POLICY, RECEIPT
from mlops import golden_split, registry
from mlops.common import DATA_DIR, dataset_info, load_program, load_records, require_local_lm, setup_mlflow

EXPERIMENT = "nightly"
FORCE = os.environ.get("FORCE") == "1"


# ---------------------------------------------------------------- 기록
def _mlflow():
    mlflow = setup_mlflow()
    mlflow.set_experiment(EXPERIMENT)
    return mlflow


def last_key(part: str) -> str | None:
    """이 단계가 지난번에 무엇으로 돌았는지 (실패한 밤은 빼고)"""
    mlflow = _mlflow()
    runs = mlflow.search_runs(experiment_names=[EXPERIMENT],
                              filter_string=f"tags.part = '{part}' and tags.decision != 'error'",
                              order_by=["start_time DESC"], max_results=1, output_format="list")
    return runs[0].data.tags.get("trigger_key") if runs else None


def record(part: str, key: str, decision: str, reason: str, report: dict | None = None, metrics: dict | None = None):
    mlflow = _mlflow()
    with mlflow.start_run(run_name=f"{part}-{time.strftime('%Y%m%d-%H%M')}"):
        mlflow.set_tags({"part": part, "trigger_key": key, "decision": decision, "reason": reason[:4000]})
        if metrics:
            mlflow.log_metrics({k: float(v) for k, v in metrics.items() if isinstance(v, (int, float))})
        if report:
            mlflow.log_dict(_jsonable(report), "report.json")
    print(f"[{part}] {decision}: {reason}")


def _jsonable(obj):
    return json.loads(json.dumps(obj, ensure_ascii=False, default=str))


def skip_if_seen(part: str, key: str) -> bool:
    if not FORCE and last_key(part) == key:
        record(part, key, "skipped", "새 데이터 없음 (지난번과 같은 데이터·champion)")
        return True
    return False


# ---------------------------------------------------------------- 지금 서빙 중인 것
def serving_receipt():
    """(프로그램, 버전 이름, champion 정보). champion이 없으면 Git 파일 기준(BUNDLED)"""
    from ai.modules.receipt import BUNDLED

    ch = registry.champion(RECEIPT)
    if ch is None:
        return BUNDLED.extractor, BUNDLED.label, None
    return load_program(registry.download_program(ch, RECEIPT)), ch["tags"]["label"], ch


def serving_adapter() -> tuple[str | None, dict | None]:
    """(서빙 중인 어댑터 이름 — vLLM에 떠 있을 때만, champion 정보)"""
    from ai.champions import _vllm_models

    ch = registry.champion(ADAPTER)
    name = ch["tags"].get("adapter_name") if ch else None
    if name and name not in _vllm_models():
        print(f"⚠ champion 어댑터 {name}가 vLLM에 없음 → 기본 모델 기준으로 비교")
        name = None
    return name, ch


def serving_judge():
    from ai.rag import policy
    from ai.rag.policy import BUNDLED_JUDGE, PolicyJudge

    ch = registry.champion(POLICY)
    if ch is None:
        return BUNDLED_JUDGE[0], BUNDLED_JUDGE[1], None
    judge = PolicyJudge()
    judge.load(str(registry.download_program(ch, POLICY)))
    return judge, ch["tags"]["label"], ch


def same_state(a, b) -> bool:
    """두 DSPy 프로그램이 같은지 (지시문·예시) → 같으면 '후보 없음'"""
    dump = lambda m: json.dumps(m.dump_state(), sort_keys=True, ensure_ascii=False, default=str)
    return dump(a) == dump(b)


def golden_stats() -> dict:
    path = Path("mlops/data/nightly_state.json")
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else {"golden_usable": False}


# ---------------------------------------------------------------- 단계
def prepare():
    from mlops.datasets import pull

    data = pull()
    stats = golden_split.refresh()
    stats["datasets"] = data
    Path("mlops/data/nightly_state.json").write_text(json.dumps(stats, ensure_ascii=False, indent=2), encoding="utf-8")
    record("prepare", stats["golden_train_sha"], "done",
           f"골든셋 {stats['golden_n']}장 (학습용 {stats['golden_train_n']} / 평가용 {stats['golden_test_n']}, "
           f"{'학습·게이트에 사용' if stats['golden_usable'] else f'{golden_split.MIN_ROWS}장 미만이라 아직 안 씀'})",
           metrics={k: v for k, v in stats.items() if isinstance(v, int)})


def dspy_receipt():
    from mlops.autogate import receipt_gate
    from mlops.evaluate import breakdown, log_to_mlflow
    from mlops.optimize import compile_program

    g = golden_stats()
    prog, label, ch = serving_receipt()
    adapter, _ = serving_adapter()
    golden_train = golden_split.load("train") if g["golden_usable"] else []
    key = f"{dataset_info(DATA_DIR)['dataset_hash']}|{g.get('golden_train_sha', '-') if golden_train else '-'}|{label}|{adapter or '-'}"
    if skip_if_seen("dspy-receipt", key):
        return
    train = load_records("train", DATA_DIR) + golden_train
    dev = load_records("dev", DATA_DIR)
    print(f"[dspy-receipt] 출발점 {label} (모델 {adapter or '기본'}) | 학습 {len(train)}장 (골든 {len(golden_train)}) / dev {len(dev)}장")
    chal, minutes = compile_program(prog, train, dev, auto="light", demos=3, threads=4, model=adapter)
    if same_state(chal, prog):
        record("dspy-receipt", key, "no-candidate", f"지금 프로그램이 최선 ({minutes:.0f}분 탐색)")
        return
    print("[dspy-receipt] 새 후보 → 자동 게이트")
    result = receipt_gate(prog, chal, adapter, adapter)
    decision = "promoted" if result["passed"] else "rejected"
    if result["passed"]:
        from mlops.evaluate import summarize
        rows = result["chal_rows"]
        run_id = log_to_mlflow(f"nightly-{time.strftime('%Y%m%d')}", "test", DATA_DIR, chal, None, summarize(rows),
                               breakdown(rows), rows, 4, tags={"stage": "nightly", "role": "challenger", "decision": decision},
                               model=adapter)
        version = registry.register(RECEIPT, run_id, registry.artifact_uri(run_id, "program/program.json"),
                                    {"source": "nightly", "previous_label": label, "reason": result["reason"]}, ch)
        result["reason"] += f" → {RECEIPT} v{version} champion"
    record("dspy-receipt", key, decision, result["reason"], report={k: v for k, v in result.items() if k != "chal_rows"})


def dspy_policy():
    from ai.rag import policy
    from ai.rag.index import get_index
    from mlops.autogate import policy_gate
    from mlops.optimize import local_lm
    from mlops.optimize_policy import compile_judge, local_only_judging
    from mlops.policy_eval import EVAL_SET, EXPERIMENT as POLICY_EXP

    raw = EVAL_SET.read_bytes()
    data = yaml.safe_load(raw)
    import hashlib
    judge, label, ch = serving_judge()
    key = f"{hashlib.sha256(raw).hexdigest()[:12]}|{get_index().version}|{label}"
    if skip_if_seen("dspy-policy", key):
        return
    dev_cases = [c for c in data["cases"] if c["split"] == "dev"]
    print(f"[dspy-policy] 출발점 {label} | dev {len(dev_cases)}건")
    chal, minutes, _ = compile_judge(dev_cases, data["today"], start_judge=judge)
    if same_state(chal, judge):
        record("dspy-policy", key, "no-candidate", f"지금 판정 프로그램이 최선 ({minutes:.0f}분 탐색)")
        return
    print("[dspy-policy] 새 후보 → 자동 게이트")
    local_only_judging(local_lm(0.0))
    result = policy_gate(judge, chal, data)
    decision = "promoted" if result["passed"] else "rejected"
    if result["passed"]:
        mlflow = setup_mlflow()
        mlflow.set_experiment(POLICY_EXP)
        with tempfile.TemporaryDirectory() as tmp, mlflow.start_run(run_name=f"nightly-{time.strftime('%Y%m%d')}") as run:
            mlflow.set_tags({"stage": "nightly", "role": "challenger", "decision": decision})
            mlflow.log_metrics({f"judge_{k}": float(v) for k, v in result["rounds"][-1]["challenger"].items()})
            p = Path(tmp) / "program.json"
            chal.save(str(p))
            mlflow.log_artifact(str(p), artifact_path="program")
            run_id = run.info.run_id
        version = registry.register(POLICY, run_id, registry.artifact_uri(run_id, "program/program.json"),
                                    {"source": "nightly", "previous_label": label, "reason": result["reason"]}, ch)
        result["reason"] += f" → {POLICY} v{version} champion"
    record("dspy-policy", key, decision, result["reason"], report=result)


def sft(out: Path):
    """QLoRA 학습 데이터: 지금 champion 프롬프트 형식 + 합성(품목 정답 포함) + 골든 학습용(쓸 만큼 쌓였을 때)"""
    import dspy

    from ai.dspy_lm import get_local_lm
    from mlops.export_sft import build, write

    g = golden_stats()
    prog, label, _ = serving_receipt()
    adapter, _ = serving_adapter()
    lm = get_local_lm(adapter)

    def items_of(text: str) -> list[dict]:
        with dspy.context(lm=lm):
            return [i.model_dump() for i in prog(text=text).receipt.items]

    golden = golden_split.load("train") if g["golden_usable"] else []
    rows, meta = build(program=prog, golden=golden, golden_items=items_of)
    meta["program"] = label
    write(rows, meta, out)
    print(f"[sft] {out} 학습 {meta['n_train']} / 손실 확인 {meta['n_eval']} (골든 {meta['n_golden']}) | 프롬프트 {label} | "
          f"sha {meta['sha256']} | 뺀 것 {meta['skipped']}")


def adapter_gate(name: str, path: str):
    from mlops.autogate import receipt_gate
    from mlops.evaluate import breakdown, log_to_mlflow, summarize
    from ai.champions import _vllm_models

    require_local_lm()
    if name not in _vllm_models():
        record(f"adapter", name, "error", f"새 어댑터 {name}가 vLLM에 없음 (night_job 재시작 확인)")
        return
    prog, label, _ = serving_receipt()
    champ_model, ch = serving_adapter()
    print(f"[adapter] {champ_model or '기본 모델'} vs {name} (프롬프트 {label})")
    result = receipt_gate(prog, prog, champ_model, name, require_golden=True)
    decision = "promoted" if result["passed"] else ("hold" if result["hold"] else "rejected")
    if result["passed"]:
        rows = result["chal_rows"]
        run_id = log_to_mlflow(f"nightly-{name}", "test", DATA_DIR, prog, None, summarize(rows), breakdown(rows), rows, 4,
                               tags={"stage": "nightly", "role": "challenger", "decision": decision}, model=name)
        ref = {"adapter_name": name, "adapter_path": path,
               "data_sha": (Path(path) / "data.sha").read_text().strip() if (Path(path) / "data.sha").exists() else ""}
        registry.log_json(run_id, ref, "adapter_ref.json")
        version = registry.register(ADAPTER, run_id, registry.artifact_uri(run_id, "adapter_ref.json"),
                                    {"source": "nightly", **ref, "reason": result["reason"]}, ch)
        result["reason"] += f" → {ADAPTER} v{version} champion (내일 아침 start_day가 붙여서 서빙)"
    record("adapter", name, decision, result["reason"], report={k: v for k, v in result.items() if k != "chal_rows"})


def summary():
    mlflow = _mlflow()
    since = int((time.time() - 16 * 3600) * 1000)
    runs = mlflow.search_runs(experiment_names=[EXPERIMENT], filter_string=f"attributes.start_time > {since}",
                              order_by=["start_time ASC"], output_format="list")
    print("=== 오늘 밤 MLOps 결과 ===")
    for r in runs:
        t = r.data.tags
        print(f"  {t.get('part', '?'):13} {t.get('decision', '?'):12} {t.get('reason', '')}")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("phase", choices=["prepare", "dspy", "sft", "adapter", "summary"])
    p.add_argument("--out", type=Path, help="sft: 학습 데이터 저장 위치")
    p.add_argument("--name", help="adapter: 새 어댑터 이름 (vLLM 모델 이름)")
    p.add_argument("--path", help="adapter: 새 어댑터 폴더")
    a = p.parse_args()
    if a.phase == "prepare":
        prepare()
    elif a.phase == "dspy":
        require_local_lm()
        for part, fn in (("dspy-receipt", dspy_receipt), ("dspy-policy", dspy_policy)):
            try:                                   # 한쪽이 실패해도 다른 쪽·학습은 계속
                fn()
            except Exception as e:
                record(part, "-", "error", f"{type(e).__name__}: {e}")
    elif a.phase == "sft":
        sft(a.out or Path.home() / "ai/sft/nightly.jsonl")
    elif a.phase == "adapter":
        adapter_gate(a.name, a.path)
    else:
        summary()


if __name__ == "__main__":
    main()
