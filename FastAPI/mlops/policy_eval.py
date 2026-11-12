"""규정 RAG 평가: 검색(기권 포함) + 영수증 규정 검토 → 지표 → MLflow (실험 policy-check).

실행 (FastAPI 폴더에서)
  python -X utf8 -m mlops.policy_eval                         # 전체 (dev+test), MLflow 기록
  python -X utf8 -m mlops.policy_eval --split dev --no-mlflow # 빠르게 확인
  python -X utf8 -m mlops.policy_eval --program mlops/data/programs/policy-xxx.json  # 후보 판정 프로그램 평가

지표
  검색   recall_at_k        답이 있는 질문에서 정답 조항이 상위 k개 안에 있는 비율
         abstain_ok         답이 없는 질문에서 기권한 비율 (목표 0.95 이상)
         false_abstain      답이 있는데 기권한 비율
         + MIN_REL 후보별 표 (기권 기준선 조정용, LLM 호출 없음)
  판정   verdict_acc        종합 판정이 기대 판정과 맞은 비율
         missed_violations  위반이어야 하는데 '준수' (게이트: 반드시 0)
         unsafe_pass        '준수'가 정답이 아닌데 '준수' (놓친 위반 + 확인 필요를 건너뜀, 게이트: 반드시 0)
         false_violations   위반이 아닌데 '위반'
         auto_pass_rate     '준수'가 정답인 케이스 중 실제로 '준수'가 나온 비율 (자동 처리율, 낮으면 과잉 신중)
         clause_acc         조항별 기대 판정 일치율
         needs_ok           꼭 물어봐야 할 정보를 needed_facts에 넣은 비율
         citation_ok        LLM이 준수·위반이라 한 판정 중 인용 검증을 통과한 비율
         code_override      코드 숫자 비교가 LLM 판정을 위반으로 바꾼 횟수 (참고)
"""
import argparse
import hashlib
import json
import tempfile
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import dspy
import yaml

from ai.rag import policy
from ai.rag.index import EMBED_MODEL, RERANK_MODEL, get_index
from ai.rag.search import MIN_REL, retrieve
from mlops.common import require_local_lm, setup_mlflow

EVAL_SET = Path("mlops/eval_sets/policy_v1.yaml")
EXPERIMENT = "policy-check"
SWEEP = [0.01, 0.02, 0.05, 0.10, 0.20, 0.30, 0.50]


def _as_list(v) -> list:
    return v if isinstance(v, list) else [v]


# ---------------------------------------------------------------- 검색
def eval_retrieval(items: list[dict], k: int) -> tuple[dict, list[dict], list[dict]]:
    rows = []
    for it in items:
        r = retrieve(it["q"], doc_codes=[it["doc"]] if it.get("doc") else None, k=k)
        got = [h.clause.id for h in r.hits]
        rows.append({"q": it["q"], "expect": it["expect"], "got": got, "top_rel": r.top_rel, "abstain": r.abstain,
                     "hit": bool(it["expect"]) and not r.abstain and any(e in got for e in it["expect"])})
    ans = [r for r in rows if r["expect"]]
    una = [r for r in rows if not r["expect"]]
    m = {
        "recall_at_k": sum(r["hit"] for r in ans) / max(len(ans), 1),
        "abstain_ok": sum(r["abstain"] for r in una) / max(len(una), 1),
        "false_abstain": sum(r["abstain"] for r in ans) / max(len(ans), 1),
    }
    # 기권 기준선 후보별: 정답 질문의 정답 조항 관련도는 r["top_rel"]로 근사 (1위가 정답인 경우가 대부분)
    sweep = [{"min_rel": t,
              "abstain_ok": sum(r["top_rel"] < t for r in una) / max(len(una), 1),
              "false_abstain": sum(r["top_rel"] < t for r in ans) / max(len(ans), 1)} for t in SWEEP]
    return m, rows, sweep


# ---------------------------------------------------------------- 판정
def eval_case(case: dict, today, judge=None) -> dict:
    """judge: 평가할 판정 프로그램 (생략하면 서버가 쓰는 champion). 넘기면 판정 캐시 없이 매번 LLM 판정 (비교가 공정하게)"""
    t0 = time.perf_counter()
    try:
        r = policy.check_receipt(case["fields"], case.get("extra") or {}, today=today, judge=judge)
        error = None
    except Exception as e:                       # 한 케이스 실패가 전체 평가를 멈추지 않게
        r, error = {"verdict": "오류", "findings": [], "needed_facts": []}, f"{e.__class__.__name__}: {e}"
    ms = (time.perf_counter() - t0) * 1000

    expect = _as_list(case["expect"])
    pred = r["verdict"]
    by_id = {f["clause_id"]: f["verdict"] for f in r["findings"]}
    clause_checks = {cid: by_id.get(cid) in _as_list(v) for cid, v in (case.get("clauses") or {}).items()}
    must_be_violation = "위반" in expect and "준수" not in expect
    return {
        "id": case["id"], "split": case["split"], "note": case.get("note", ""),
        "expect": expect, "pred": pred, "ok": pred in expect,
        "missed_violation": must_be_violation and pred == "준수",
        "unsafe_pass": "준수" not in expect and pred == "준수",
        "false_violation": "위반" not in expect and pred == "위반",
        "strict_pass": expect == ["준수"], "auto_pass": expect == ["준수"] and pred == "준수",
        "clauses": clause_checks, "clause_got": by_id,
        "needs_ok": all(n in r["needed_facts"] for n in case.get("needs") or []) if case.get("needs") else None,
        "needed_facts": r["needed_facts"],
        "findings": r["findings"], "ms": ms, "error": error,
    }


def summarize(rows: list[dict]) -> dict:
    n = max(len(rows), 1)
    clause = [v for r in rows for v in r["clauses"].values()]
    needs = [r["needs_ok"] for r in rows if r["needs_ok"] is not None]
    strict = [r for r in rows if r["strict_pass"]]
    llm_sure = [f for r in rows for f in r["findings"] if f.get("llm_verdict") in ("준수", "위반")]
    return {
        "verdict_acc": sum(r["ok"] for r in rows) / n,
        "missed_violations": sum(r["missed_violation"] for r in rows),
        "unsafe_pass": sum(r["unsafe_pass"] for r in rows),
        "false_violations": sum(r["false_violation"] for r in rows),
        "auto_pass_rate": sum(r["auto_pass"] for r in strict) / max(len(strict), 1),
        "clause_acc": sum(clause) / max(len(clause), 1),
        "needs_ok": sum(needs) / max(len(needs), 1),
        "citation_ok": sum(not any("인용" in c for c in f["checks"]) for f in llm_sure) / max(len(llm_sure), 1),
        "code_override": sum(any("코드 계산으로 위반" in c for c in f["checks"]) for r in rows for f in r["findings"]),
        "errors": sum(r["error"] is not None for r in rows),
        "latency_ms": sum(r["ms"] for r in rows) / n,
    }


# ---------------------------------------------------------------- 출력·기록
def print_report(rm: dict, rrows: list[dict], sweep: list[dict], cm: dict, crows: list[dict]):
    print("\n[검색]")
    print(f"  recall@k {rm['recall_at_k']:.1%} | 기권 정답(답 없는 질문) {rm['abstain_ok']:.1%} | 잘못된 기권 {rm['false_abstain']:.1%}")
    for r in rrows:
        bad = (r["expect"] and not r["hit"]) or (not r["expect"] and not r["abstain"])
        if bad:
            print(f"  ❌ {r['q']} | 기대 {r['expect'] or '기권'} | 결과 {'기권' if r['abstain'] else r['got']} (관련도 {r['top_rel']:.3f})")
    una = sorted(r["top_rel"] for r in rrows if not r["expect"])
    ans = sorted(r["top_rel"] for r in rrows if r["expect"])
    print(f"  관련도 분포: 답 없는 질문 최고 {una[-1]:.3f} / 답 있는 질문 최저 {ans[0]:.3f}  (이 사이에 기준선을 두면 둘 다 맞음)")
    print("  MIN_REL 후보:", " | ".join(f"{s['min_rel']:.2f} → 기권 {s['abstain_ok']:.0%}·오기권 {s['false_abstain']:.0%}" for s in sweep))

    print("\n[판정]")
    print(f"  정확도 {cm['verdict_acc']:.1%} | 놓친 위반 {cm['missed_violations']} | 잘못된 준수 {cm['unsafe_pass']} | 거짓 위반 {cm['false_violations']} | "
          f"자동 처리율 {cm['auto_pass_rate']:.1%} | 조항 {cm['clause_acc']:.1%} | 필요 정보 {cm['needs_ok']:.1%} | "
          f"인용 {cm['citation_ok']:.1%} | 코드 보정 {cm['code_override']} | 오류 {cm['errors']} | {cm['latency_ms']:.0f}ms")
    for r in crows:
        if not r["ok"] or not all(r["clauses"].values()) or r["needs_ok"] is False:
            mark = "🚨 놓친 위반" if r["missed_violation"] else "⚠️ 잘못된 준수" if r["unsafe_pass"] else "❌"
            print(f"  {mark} {r['id']} {r['note']} | 기대 {'/'.join(r['expect'])} → {r['pred']}"
                  f" | 조항 {r['clause_got']} | 필요 정보 {r['needed_facts']}" + (f" | {r['error']}" if r["error"] else ""))


def log_to_mlflow(args, info: dict, rm: dict, sweep: list, rrows: list, cm: dict, crows: list):
    mlflow = setup_mlflow()
    mlflow.set_experiment(EXPERIMENT)
    with mlflow.start_run(run_name=args.name or f"{policy.JUDGE_VERSION}-{args.split or 'all'}"):
        mlflow.log_params({**info, "split": args.split or "all", "k": args.k, "min_rel": MIN_REL,
                           "judge_version": policy.JUDGE_VERSION, "program": args.program or "-",
                           "embed_model": EMBED_MODEL, "rerank_model": RERANK_MODEL})
        mlflow.log_metrics({f"retrieval_{k}": v for k, v in rm.items()})
        mlflow.log_metrics({f"judge_{k}": v for k, v in cm.items()})
        with tempfile.TemporaryDirectory() as tmp:
            for name, data in (("cases.jsonl", crows), ("retrieval.jsonl", rrows)):
                p = Path(tmp) / name
                p.write_text("\n".join(json.dumps(x, ensure_ascii=False, default=str) for x in data), encoding="utf-8")
                mlflow.log_artifact(str(p))
            p = Path(tmp) / "min_rel_sweep.json"
            p.write_text(json.dumps(sweep, ensure_ascii=False, indent=2), encoding="utf-8")
            mlflow.log_artifact(str(p))
            if args.program:
                mlflow.log_artifact(args.program)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--set", default=str(EVAL_SET))
    ap.add_argument("--split", choices=["dev", "test"], default=None, help="판정 케이스만 나눔 (검색은 항상 전체)")
    ap.add_argument("--program", default=None, help="평가할 판정 프로그램 (없으면 서버가 쓰는 것)")
    ap.add_argument("--k", type=int, default=3)
    ap.add_argument("--threads", type=int, default=2, help="케이스 동시 실행 수 (케이스 안에서도 조항 4개씩 동시)")
    ap.add_argument("--name", default=None)
    ap.add_argument("--no-mlflow", action="store_true")
    args = ap.parse_args()

    raw = Path(args.set).read_bytes()
    data = yaml.safe_load(raw)
    info = {"eval_set": Path(args.set).name, "eval_hash": hashlib.sha256(raw).hexdigest()[:12],
            "index_version": get_index().version}

    # 평가는 로컬 LLM만 (GMS로 넘어가면 점수가 섞임) — 판정 모듈의 LLM 호출을 로컬 전용으로 바꿔 끼움
    lm = require_local_lm()

    def local_only(module, _model=None, **inputs):
        with dspy.context(lm=lm):
            return module(**inputs)

    policy.run_with_fallback = local_only
    if args.program:
        judge = policy.PolicyJudge()
        judge.load(args.program)
        policy._judge, policy.JUDGE_VERSION = judge, f"candidate:{Path(args.program).stem}"

    rm, rrows, sweep = eval_retrieval(data["retrieval"], args.k)
    cases = [c for c in data["cases"] if args.split is None or c["split"] == args.split]
    with ThreadPoolExecutor(max_workers=args.threads) as pool:
        crows = list(pool.map(lambda c: eval_case(c, data["today"]), cases))
    cm = summarize(crows)

    print(f"평가셋 {info['eval_set']} ({info['eval_hash']}) | 색인 {info['index_version']} | 판정 {policy.JUDGE_VERSION} | "
          f"검색 {len(rrows)}문항 · 판정 {len(crows)}건")
    print_report(rm, rrows, sweep, cm, crows)
    if not args.no_mlflow:
        log_to_mlflow(args, info, rm, sweep, rrows, cm, crows)
        print(f"\nMLflow 실험 '{EXPERIMENT}'에 기록했습니다")


if __name__ == "__main__":
    main()