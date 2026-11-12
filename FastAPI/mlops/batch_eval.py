"""영수증 여러 장 처리의 'LLM 없는' 부분 평가 → 지표 → MLflow (실험 receipt-batch).

실행 (FastAPI 폴더에서)
  python -X utf8 -m mlops.batch_eval                    # receipts_v2 OCR 텍스트 + 인원 시나리오, MLflow 기록
  python -X utf8 -m mlops.batch_eval --no-mlflow        # 빠르게 확인
  (결제 시각·결제 수단 정답이 없다고 나오면 gen_receipts를 같은 seed로 다시 돌려 labels.jsonl만 바꾸면 됨 — 이미지·OCR 그대로)

지표 (게이트)
  원문 대조(싼 검증)  cheap_pass        정답 값이 싼 검증을 통과한 비율 (높을수록 GMS 호출이 줄어듦)
                     cheap_catch       금액을 틀리게 바꾼 값을 싼 검증이 잡은 비율 (게이트: 0.95 이상)
                     supply_vat_found  공급가액·부가세를 찾은 비율
                     pay_time_acc      결제 시각 정답률 (정답이 있을 때)
                     pay_method_acc    결제 수단 정답률 (카드 / 간편결제 / 현금)
  인원 추정           range_cover       참 인원이 추정 범위 안에 든 비율 (도움말 품질)
                     inflate_catch     부풀린 입력(참 인원 + 3)을 잡은 비율
                     false_inflate     참 인원을 적었는데 부풀림으로 본 비율 (게이트: 0)
"""
import argparse
import json
import random
from pathlib import Path

from ai.receipts.checks import cheap_checks
from ai.receipts.headcount import estimate_headcount, inflation_issue
from ai.receipts.parse import parse_details

DATA = Path("mlops/data/receipts_v2")
EXPERIMENT = "receipt-batch"
PAY_NAMES = {"신용카드": "카드", "카카오페이": "간편결제", "현금": "현금"}
GATES = {"cheap_catch": (">=", 0.95), "false_inflate": ("==", 0.0)}


def eval_checks(ocr_file: str) -> tuple[dict, list[dict]]:
    labels = {json.loads(x)["id"]: json.loads(x) for x in (DATA / "labels.jsonl").read_text(encoding="utf-8").splitlines() if x}
    texts = {json.loads(x)["id"]: json.loads(x)["text"] for x in (DATA / ocr_file).read_text(encoding="utf-8").splitlines() if x}
    rows = []
    for rid, rec in labels.items():
        text = texts.get(rid)
        if text is None:
            continue
        label, meta = rec["label"], rec.get("meta") or {}
        d = parse_details(text, label["date"])
        ok = cheap_checks(label, [], d, text)
        wrong = {**label, "total": label["total"] + 1000}            # 흔한 오답: 천 단위 하나 틀림
        caught = cheap_checks(wrong, [], d, text)
        rows.append({
            "id": rid, "pass": not ok, "reasons": ok, "caught": bool(caught),
            "supply_vat": d.value("supply") is not None and d.value("vat") is not None,
            "pay_time": None if "pay_time" not in meta else d.value("pay_time") == meta["pay_time"],
            "pay_method": None if "pay" not in meta else d.value("pay_method") == PAY_NAMES.get(meta["pay"]),
        })
    n = max(len(rows), 1)
    known_t = [r["pay_time"] for r in rows if r["pay_time"] is not None]
    known_m = [r["pay_method"] for r in rows if r["pay_method"] is not None]
    return {
        "receipts": len(rows),
        "cheap_pass": sum(r["pass"] for r in rows) / n,
        "cheap_catch": sum(r["caught"] for r in rows) / n,
        "supply_vat_found": sum(r["supply_vat"] for r in rows) / n,
        "pay_time_acc": sum(known_t) / len(known_t) if known_t else -1,
        "pay_method_acc": sum(known_m) / len(known_m) if known_m else -1,
    }, rows


def scenarios(rng: random.Random, n: int = 400) -> list[tuple[int, list[dict], str]]:
    """참 인원이 정해진 품목 목록 (사용자 우려: 카페 3명이 음료 3잔+케이크, 식당 공용 요리)"""
    out = []
    for _ in range(n):
        people = rng.randint(1, 8)
        kind = rng.choice(["cafe", "meal", "shared", "portion"])
        if kind == "cafe":
            drinks = people + (1 if rng.random() < 0.15 else 0)              # 가끔 한 사람이 두 잔
            items = [{"name": "아메리카노", "quantity": drinks, "kind": "1인 음료"}]
            if rng.random() < 0.6:
                items.append({"name": "치즈케이크", "quantity": rng.randint(1, 2), "kind": "디저트·사이드"})
        elif kind == "meal":
            skip = 1 if people > 2 and rng.random() < 0.1 else 0             # 가끔 한 사람은 안 시킴
            items = [{"name": "김치찌개", "quantity": people - skip, "kind": "1인 식사"},
                     {"name": "공기밥", "quantity": rng.randint(0, 2), "kind": "디저트·사이드"}]
        elif kind == "shared":
            items = [{"name": "삼겹살", "quantity": max(1, people - rng.randint(0, 1)), "kind": "공용 요리"},
                     {"name": "소주", "quantity": rng.randint(1, 4), "kind": "주류"}]
        else:
            items = [{"name": f"모둠회 {people + rng.choice([-1, 0, 0, 1])}인분", "quantity": 1, "kind": "공용 요리"}]
        out.append((people, items, kind))
    return out


def eval_headcount(seed: int) -> dict:
    rows = []
    for people, items, kind in scenarios(random.Random(seed)):
        est = estimate_headcount(items)
        rows.append({"kind": kind, "estimated": est is not None,
                     "cover": bool(est) and est["min"] <= people <= est["max"],
                     "catch": bool(inflation_issue(people + 3, est)) if est else None,
                     "false": bool(inflation_issue(people, est)) if est else False})
    est_rows = [r for r in rows if r["estimated"]]
    m = max(len(est_rows), 1)
    out = {"scenarios": len(rows), "estimated_rate": len(est_rows) / max(len(rows), 1),
           "range_cover": sum(r["cover"] for r in est_rows) / m,
           "inflate_catch": sum(bool(r["catch"]) for r in est_rows) / m,
           "false_inflate": sum(r["false"] for r in est_rows) / m}
    for kind in ("cafe", "meal", "shared", "portion"):          # 종류별 (공용 요리는 추정 안 함이 정상)
        k = [r for r in rows if r["kind"] == kind]
        e = [r for r in k if r["estimated"]]
        out[f"{kind}_estimated"] = len(e) / max(len(k), 1)
        out[f"{kind}_cover"] = sum(r["cover"] for r in e) / max(len(e), 1)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ocr", default="ocr.jsonl")
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--no-mlflow", action="store_true")
    args = ap.parse_args()

    checks, rows = eval_checks(args.ocr)
    head = eval_headcount(args.seed)
    metrics = {**checks, **head}
    for k, v in metrics.items():
        print(f"  {k:<18} {v:.3f}" if isinstance(v, float) else f"  {k:<18} {v}")
    failed = [k for k, (op, t) in GATES.items()
              if not (metrics[k] >= t if op == ">=" else metrics[k] == t)]
    print("게이트:", "통과" if not failed else f"실패 {failed}")
    misses = [r for r in rows if not r["pass"]][:10]
    if misses:
        print("싼 검증에 걸린 정답 예 (GMS로 가는 장):")
        for r in misses:
            print("   ", r["id"], r["reasons"])

    if not args.no_mlflow:
        from mlops.common import setup_mlflow
        mlflow = setup_mlflow()
        mlflow.set_experiment(EXPERIMENT)
        with mlflow.start_run(run_name=f"batch-{args.ocr}"):
            mlflow.log_params({"ocr_file": args.ocr, "seed": args.seed})
            mlflow.log_metrics({k: float(v) for k, v in metrics.items()})
            mlflow.set_tag("gate", "pass" if not failed else "fail")


if __name__ == "__main__":
    main()
