"""규정 판정 DSPy 최적화: dev 케이스로 판정 지시문 후보를 만들고 골라 도전자 판정 프로그램을 만든다.

  python -X utf8 -m mlops.optimize_policy                 # MIPROv2 light, 지시문만 (예시 0개)
  python -X utf8 -m mlops.optimize_policy --auto medium   # 후보를 더 많이 탐색 (더 오래 걸림)

설계
  - 단위는 '케이스'(영수증 하나 → 종합 판정). 조항별 정답은 일부 케이스에만 있어서 조항 단위 학습셋은 못 만듦
    → 케이스를 통째로 돌리는 래퍼(CasePolicy) 안의 판정 모듈(PolicyJudge)만 최적화
  - 지시문만 최적화 (예시 0개): 판정이 check_receipt 안의 스레드에서 돌아서 DSPy가 예시용 실행 기록을 못 모으고,
    조항 원문 + 사실 JSON이 길어서 예시를 넣으면 프롬프트가 크게 늘어남
  - 검색 결과는 같은 사실이면 늘 같음 → 캐시 (시도마다 LLM 판정만 다시 돌림)
  - test는 여기서 절대 안 씀 → policy_eval --split test 로 base와 비교해서 승격 판정

점수 (케이스 하나): 기대 판정과 맞음 1.0 / 틀렸지만 사람이 한 번 더 보는 쪽(확인 필요·위반) 0.2 / 잘못된 준수 0.0
결과: mlops/data/programs/policy-miprov2-날짜-시각.json + MLflow 실험 policy-check (stage=optimize)
"""
import argparse
import hashlib
import json
import logging
import time
from pathlib import Path

import dspy
import yaml
from dspy.teleprompt import MIPROv2

from ai.rag import policy
from mlops.common import require_local_lm, setup_mlflow
from mlops.optimize import local_lm
from mlops.policy_eval import EVAL_SET, EXPERIMENT

logging.getLogger("LiteLLM").setLevel(logging.WARNING)
OUT_DIR = Path("mlops/data/programs")       # git에 안 올라감 (원본은 MLflow artifact)


def _as_list(v) -> list:
    return v if isinstance(v, list) else [v]


class CasePolicy(dspy.Module):
    """케이스 하나(영수증 추출 결과 + 사용자가 준 정보) → 종합 판정. 최적화 대상은 안의 judge뿐"""

    def __init__(self, today):
        super().__init__()
        self.judge = policy.PolicyJudge()
        self.today = today

    def forward(self, fields: dict, extra: dict):
        r = policy.check_receipt(fields, extra, today=self.today, judge=self.judge)
        return dspy.Prediction(verdict=r["verdict"], needed_facts=r["needed_facts"])


def metric(example, pred, trace=None) -> float:
    expect = _as_list(example.expect)
    if pred.verdict in expect:
        return 1.0
    if pred.verdict == "준수":                  # 준수가 정답이 아닌데 준수 = 잘못된 준수 (놓친 위반 포함) → 가장 나쁨
        return 0.0
    return 0.2                                  # 과잉 신중·거짓 위반: 틀렸지만 사람이 한 번 더 봄


def to_examples(cases: list[dict]) -> list[dspy.Example]:
    return [dspy.Example(id=c["id"], note=c.get("note", ""), fields=c["fields"], extra=c.get("extra") or {},
                         expect=c["expect"]).with_inputs("fields", "extra") for c in cases]


def cache_retrieval() -> dict:
    """검색(질문 6~7개 × 재순위)은 같은 사실이면 결과가 같음 → 한 번만 돌리고 재사용"""
    original = policy._collect
    cache: dict = {}

    def cached(known, doc_codes, k):
        key = json.dumps([known, doc_codes, k], ensure_ascii=False, sort_keys=True, default=str)
        if key not in cache:
            cache[key] = original(known, doc_codes, k)
        return cache[key]

    policy._collect = cached
    return cache


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--set", default=str(EVAL_SET))
    ap.add_argument("--auto", choices=["light", "medium", "heavy"], default="light")
    ap.add_argument("--threads", type=int, default=3, help="케이스 동시 실행 수 (케이스 안에서도 조항 4개씩 동시)")
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()

    raw = Path(args.set).read_bytes()
    data = yaml.safe_load(raw)
    dev_cases = [c for c in data["cases"] if c["split"] == "dev"]
    dev = to_examples(dev_cases)
    eval_hash = hashlib.sha256(raw).hexdigest()[:12]

    require_local_lm()                                     # 로컬 LLM 설정 확인
    task_lm = local_lm(0.0)                                # 판정: 항상 같은 답
    prompt_lm = local_lm(0.7, max_tokens=4096)             # 지시문 후보 작성: 다양하게
    dspy.configure(lm=task_lm)

    # 판정은 로컬 LLM만 (GMS로 넘어가면 점수가 섞임). check_receipt 안의 스레드에서도 같은 LM을 쓰게 직접 지정
    def local_only(module, **inputs):
        with dspy.context(lm=task_lm):
            return module(**inputs)

    policy.run_with_fallback = local_only
    cache = cache_retrieval()

    optimizer = MIPROv2(
        metric=metric,
        prompt_model=prompt_lm,
        task_model=task_lm,
        auto=args.auto,
        max_bootstrapped_demos=0,                          # 지시문만 최적화 (예시 0개)
        max_labeled_demos=0,
        num_threads=args.threads,
        seed=args.seed,
    )
    print(f"MIPROv2({args.auto}) 시작: 평가셋 {Path(args.set).name} ({eval_hash}) dev {len(dev)}건 "
          f"(준수가 정답 {sum(_as_list(c['expect']) == ['준수'] for c in dev_cases)}건)")
    start = time.time()
    # dev가 27건뿐이라 미니배치(기본 35건) 없이 시도마다 dev 전체로 채점, 학습셋도 dev (test는 승격 판정용으로 아낌)
    optimized = optimizer.compile(CasePolicy(data["today"]), trainset=dev, valset=dev, minibatch=False)
    minutes = (time.time() - start) / 60

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    name = f"policy-miprov2-{time.strftime('%Y%m%d-%H%M')}"
    path = OUT_DIR / f"{name}.json"
    optimized.judge.save(str(path))                        # PolicyJudge만 저장 → policy_eval --program 으로 바로 불러짐

    instructions = optimized.judge.judge.signature.instructions
    changed = instructions.strip() != policy.JudgeClause.instructions.strip()
    print(f"\n최적화 완료 ({minutes:.1f}분, 검색 캐시 {len(cache)}건) → {path}")
    print(f"[지시문 {'바뀜' if changed else '그대로 (기본 프로그램이 선택됨)'}]")
    print(instructions)

    mlflow = setup_mlflow()
    mlflow.set_experiment(EXPERIMENT)
    with mlflow.start_run(run_name=name):
        mlflow.set_tags({"stage": "optimize", "optimizer": "MIPROv2", "auto": args.auto, "max_demos": "0",
                         "seed": str(args.seed), "instructions_changed": str(changed)})
        mlflow.log_params({"eval_set": Path(args.set).name, "eval_hash": eval_hash, "dev_n": len(dev),
                           "opt_minutes": f"{minutes:.1f}"})
        mlflow.log_artifact(str(path))
    print(f"MLflow 실험 '{EXPERIMENT}'에 기록했습니다\n"
          f"다음: python -X utf8 -m mlops.policy_eval --split test --program {path.as_posix()} --name {name}-test")


if __name__ == "__main__":
    main()