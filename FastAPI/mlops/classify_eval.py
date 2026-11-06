"""문서 분류 평가: docs_v1 → 지표 → MLflow (실험 doc-classify).

실행 (FastAPI 폴더에서, 먼저 python -X utf8 -m mlops.gen_docs)
  python -X utf8 -m mlops.classify_eval                         # 전체 (dev+test), MLflow 기록
  python -X utf8 -m mlops.classify_eval --split dev --no-mlflow # 빠르게 확인
  python -X utf8 -m mlops.classify_eval --program mlops/data/programs/classify-xxx.json  # 후보 프로그램

지표 (노드 분기 기준 = 확신도 '보통' 미만이면 '기타')
  accuracy          노드가 보낸 분기가 정답과 같은 비율
  model_accuracy    모델이 고른 종류 자체의 정확도 (확신도 무시, 참고)
  wrong_route       알려진 종류(영수증 등)인데 다른 '알려진 종류'로 보냄 → 엉뚱한 처리로 감 (게이트: 반드시 0)
  other_leak        '기타' 문서를 알려진 종류로 보냄 (메뉴판을 영수증으로 등, 게이트: 반드시 0)
  human_rate        알려진 종류인데 '기타'(사람 확인)로 보낸 비율 (낮을수록 자동 처리 많음)
  evidence_ok       근거 문구가 원문에 실제로 있는 비율
  + 종류별 precision / recall, 혼동 행렬
"""
import argparse
import hashlib
import json
import tempfile
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import dspy

from ai.modules import classify
from mlops.common import require_local_lm, setup_mlflow, squash

DATA = Path("mlops/data/docs_v1/docs.jsonl")
EXPERIMENT = "doc-classify"
TYPES = classify.DOC_TYPES
KNOWN = [t for t in TYPES if t != "기타"]


def load_docs(path: Path, split: str | None) -> list[dict]:
    if not path.exists():
        raise SystemExit(f"{path}가 없습니다. 먼저 python -X utf8 -m mlops.gen_docs 를 실행하세요")
    rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    return [r for r in rows if split is None or r["split"] == split]


def eval_doc(doc: dict, program, runner, min_conf: str) -> dict:
    t0 = time.perf_counter()
    try:
        r = classify.classify_document(doc["text"], program=program, runner=runner)
        pred, conf, ev, error = r.doc_type, r.confidence, r.evidence, None
    except Exception as e:                       # 한 문서 실패가 전체 평가를 멈추지 않게 → '기타' 취급
        pred, conf, ev, error = "기타", "낮음", "", f"{e.__class__.__name__}: {e}"
    # 노드와 같은 규칙: 확신도가 기준보다 낮으면 '기타'
    low = classify.CONFIDENCE.index(conf) > classify.CONFIDENCE.index(min_conf)
    return {"id": doc["id"], "source": doc["source"], "expect": doc["doc_type"], "pred": pred,
            "route": "기타" if low else pred, "confidence": conf, "evidence": ev,
            "evidence_ok": bool(ev) and squash(ev) in squash(doc["text"]),
            "error": error, "ms": round((time.perf_counter() - t0) * 1000)}


def summarize(rows: list[dict]) -> tuple[dict, dict]:
    n = max(len(rows), 1)
    known = [r for r in rows if r["expect"] != "기타"]
    other = [r for r in rows if r["expect"] == "기타"]
    m = {
        "accuracy": sum(r["route"] == r["expect"] for r in rows) / n,
        "model_accuracy": sum(r["pred"] == r["expect"] for r in rows) / n,
        "wrong_route": sum(r["route"] in KNOWN and r["route"] != r["expect"] for r in known),
        "other_leak": sum(r["route"] in KNOWN for r in other),
        "human_rate": sum(r["route"] == "기타" for r in known) / max(len(known), 1),
        "evidence_ok": sum(r["evidence_ok"] for r in rows if r["pred"] != "기타")
                       / max(sum(r["pred"] != "기타" for r in rows), 1),
        "errors": sum(r["error"] is not None for r in rows),
        "latency_ms_avg": sum(r["ms"] for r in rows) / n,
    }
    per = {}
    for t in TYPES:
        tp = sum(r["route"] == t and r["expect"] == t for r in rows)
        pp = sum(r["route"] == t for r in rows)
        ap = sum(r["expect"] == t for r in rows)
        per[t] = {"precision": tp / pp if pp else 0.0, "recall": tp / ap if ap else 0.0, "n": ap}
    return m, per


def confusion(rows: list[dict]) -> dict:
    """행 = 정답, 열 = 노드가 보낸 분기"""
    return {e: {p: sum(r["expect"] == e and r["route"] == p for r in rows) for p in TYPES} for e in TYPES}


def print_report(info: dict, m: dict, per: dict, conf: dict, rows: list[dict]):
    print(f"평가셋 {info['data']} ({info['data_hash']}) | {info['n']}개 | 모델 {info['classify_version']} | 기준 확신도 {info['min_confidence']}")
    print(f"정확도 {m['accuracy']:.1%} (모델 자체 {m['model_accuracy']:.1%}) | 엉뚱한 분기 {m['wrong_route']} | "
          f"기타→알려진 종류 {m['other_leak']} | 사람 확인 {m['human_rate']:.1%} | 근거 OK {m['evidence_ok']:.1%} | "
          f"오류 {m['errors']} | 평균 {m['latency_ms_avg']:.0f}ms")
    print("\n종류별        precision  recall   n")
    for t, v in per.items():
        print(f"  {t:<8} {v['precision']:>9.1%} {v['recall']:>7.1%} {v['n']:>4}")
    print("\n혼동 행렬 (행=정답, 열=분기)")
    print(" " * 10 + "".join(f"{t[:4]:>7}" for t in TYPES))
    for e in TYPES:
        print(f"  {e[:6]:<8}" + "".join(f"{conf[e][p]:>7}" for p in TYPES))
    bad = [r for r in rows if r["route"] != r["expect"]]
    if bad:
        print("\n틀린 문서")
        for r in bad:
            mark = "⚠️" if r["route"] in KNOWN else "  "      # ⚠️ = 엉뚱한 종류로 감 (사람 확인이 아니라)
            print(f" {mark}{r['id']} {r['source']:<22} 정답 {r['expect']} → {r['route']} "
                  f"(모델 {r['pred']}/{r['confidence']}) 근거 '{r['evidence'][:20]}'{' ' + r['error'] if r['error'] else ''}")


def log_to_mlflow(args, info, m, per, conf, rows):
    mlflow = setup_mlflow()
    mlflow.set_experiment(EXPERIMENT)
    with mlflow.start_run(run_name=args.name or f"{info['classify_version']}-{args.split or 'all'}"):
        mlflow.log_params(info)
        mlflow.log_metrics(m)
        # 종류 이름이 한글이라 지표 이름엔 영어 키 사용
        keys = {"영수증": "receipt", "세금계산서": "tax_invoice", "출장 정산서": "trip", "휴가 신청서": "leave", "기타": "other"}
        mlflow.log_metrics({f"{k}_{keys[t]}": v[k] for t, v in per.items() for k in ("precision", "recall")})
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "classify_rows.json"
            p.write_text(json.dumps({"rows": rows, "confusion": conf}, ensure_ascii=False, indent=1), encoding="utf-8")
            mlflow.log_artifact(str(p))
        if args.program:
            mlflow.log_artifact(args.program)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default=str(DATA))
    ap.add_argument("--split", choices=["dev", "test"], default=None)
    ap.add_argument("--program", default=None, help="평가할 분류 프로그램 (없으면 서버가 쓰는 것)")
    ap.add_argument("--min-confidence", choices=["높음", "보통"], default="보통")
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--name", default=None)
    ap.add_argument("--no-mlflow", action="store_true")
    args = ap.parse_args()

    raw = Path(args.data).read_bytes() if Path(args.data).exists() else b""
    docs = load_docs(Path(args.data), args.split)

    # 평가는 로컬 LLM만 (GMS로 넘어가면 점수가 섞임)
    lm = require_local_lm()

    def local_only(module, **inputs):
        with dspy.context(lm=lm):
            return module(**inputs)

    program, version = None, classify.CLASSIFY_VERSION
    if args.program:
        program = classify.DocClassifier()
        program.load(args.program)
        version = f"candidate:{Path(args.program).stem}"

    with ThreadPoolExecutor(max_workers=args.threads) as pool:
        rows = list(pool.map(lambda d: eval_doc(d, program, local_only, args.min_confidence), docs))

    info = {"data": Path(args.data).parent.name, "data_hash": hashlib.sha256(raw).hexdigest()[:12],
            "n": len(rows), "split": args.split or "all", "classify_version": version,
            "min_confidence": args.min_confidence}
    m, per = summarize(rows)
    conf = confusion(rows)
    print_report(info, m, per, conf, rows)
    if not args.no_mlflow:
        log_to_mlflow(args, info, m, per, conf, rows)
        print(f"\nMLflow 기록 완료 (실험 {EXPERIMENT})")


if __name__ == "__main__":
    main()