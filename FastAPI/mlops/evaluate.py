"""5-4 평가: 영수증 추출 프로그램을 OCR 텍스트셋에 돌려 채점하고 MLflow에 기록한다.

  python -X utf8 -m mlops.evaluate --no-mlflow                         # 기록 없이 화면으로만 (기본 v2 dev)
  python -X utf8 -m mlops.evaluate                                     # v2 dev 평가 + 기록
  python -X utf8 -m mlops.evaluate --split test
  python -X utf8 -m mlops.evaluate --data mlops/data/receipts          # v1 데이터셋
  python -X utf8 -m mlops.evaluate --program mlops/out/opt.json --name opt   # 5-5 최적화본

평가에서는 GMS 대체 없이 로컬 LLM만 쓴다.
평가 대상은 '우리 모델'이라, 중간에 GMS 답이 섞이면 점수가 오염되기 때문.
다른 모델(예: QLoRA 어댑터)을 평가하려면 앞에 LOCAL_LLM_MODEL=receipt-lora 를 붙여 실행.

지어낸 오답(invented): 틀렸는데 그 값이 OCR 텍스트 어디에도 없는 경우 (store·total)
  예) OCR에 가게 이름 줄이 없는데 "달빛커피 강남역점"처럼 그럴듯한 이름을 만들어냄
  → 틀린 걸 사람이 알아채기 어려운 오류라, 정답률과 따로 세어 승격 게이트에서 막는다 (2026-11-06 QLoRA 평가에서 발견)
"""
import argparse
import json
import logging
import tempfile
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import dspy

from core.config import settings
from mlops.build_textset import ocr_ceiling
from mlops.common import (
    DATA_DIR, FIELDS, dataset_info, load_program, load_records, require_local_lm, score_fields, setup_mlflow, squash,
)

logging.getLogger("LiteLLM").setLevel(logging.WARNING)   # 요청마다 찍히는 로그 줄이기

# 조건별 분석 기준 (gen_receipts.py가 만든 meta 값 기준)
GROUPS = {
    "photo": lambda m: "?" if m.get("photo") is None else ("사진" if m["photo"] else "스캔"),
    "date_fmt": lambda m: m.get("date_fmt", "?"),
    "money": lambda m: m.get("money", "?"),
    "header": lambda m: m.get("header", "?"),
    "discount": lambda m: "?" if "discount" not in m else ("할인 있음" if m["discount"] else "할인 없음"),
    "kind": lambda m: m.get("kind", "?"),
}


def grounded(field: str, value, text: str) -> bool | None:
    """예측값이 OCR 텍스트에 보이는지 (띄어쓰기·쉼표 무시). 판단할 수 없는 필드(date: 표기 형식이 다양함)는 None"""
    if value in (None, ""):
        return True                                # 빈 값은 지어낸 게 아님 (모른다고 한 것)
    if field == "store":
        return squash(value) in squash(text)
    if field == "total":
        plain = squash(text).replace(",", "")
        return str(value) in plain
    return None


def predict_one(program, lm, rec: dict) -> dict:
    """한 장 추출 + 채점. 실패해도 예외를 밖으로 던지지 않고 결과에 기록 (한 장 때문에 전체가 멈추지 않게)"""
    start = time.perf_counter()
    pred, error = None, None
    try:
        with dspy.context(lm=lm):                  # 이 스레드에서만 로컬 LM 사용
            pred = program(text=rec["text"]).receipt
    except Exception as e:
        error = f"{type(e).__name__}: {e}"[:300]
    ms = round((time.perf_counter() - start) * 1000)

    scores = score_fields(pred, rec["label"])
    ceiling = ocr_ceiling(rec)                     # store/total 정답 글자가 OCR 텍스트에 보이는지
    # 틀린 필드 원인: 정답 글자가 OCR 텍스트에 아예 없으면 OCR 탓, 있는데 틀렸으면 LLM 탓
    blame = {k: ("ocr" if ceiling.get(k) is False else "llm") for k, ok in scores.items() if not ok}
    # 지어낸 오답: 틀렸고 예측값이 OCR 텍스트에 없음 / OCR 너머 교정: 맞혔는데 정답이 OCR 텍스트에 없음 (오타를 고친 것)
    values = pred.model_dump() if pred is not None else {}
    invented = [k for k, ok in scores.items() if not ok and grounded(k, values.get(k), rec["text"]) is False]
    corrected = [k for k, ok in scores.items() if ok and ceiling.get(k) is False]
    return {
        "id": rec["id"], "meta": rec["meta"], "label": rec["label"],
        "pred": pred.model_dump() if pred is not None else None,
        "error": error, "scores": scores, "all_ok": all(scores.values()),
        "blame": blame, "ceiling": ceiling, "ms": ms,
        "invented": invented, "corrected": corrected,
    }


def run_eval(program, records: list[dict], threads: int = 4, lm=None) -> list[dict]:
    """여러 장을 동시에 요청 (vLLM이 묶어서 처리해 훨씬 빠름). 결과 순서는 입력 순서 그대로
    lm: 평가할 LM (생략하면 .env의 로컬 모델). 승격 게이트에서 모델 두 개를 한 번에 비교할 때 사용"""
    lm = lm or require_local_lm()
    with ThreadPoolExecutor(max_workers=threads) as pool:
        return list(pool.map(lambda r: predict_one(program, lm, r), records))


def _rate(rows: list[dict], fn) -> float:
    return round(sum(1 for r in rows if fn(r)) / len(rows), 4) if rows else 0.0


def _stats(group: list[dict]) -> dict:
    # lambda의 k=k: 반복문 변수를 그 시점 값으로 고정 (안 하면 전부 마지막 값 'date'로 계산됨)
    return {
        "n": len(group),
        "acc_all": _rate(group, lambda r: r["all_ok"]),
        **{f"acc_{k}": _rate(group, lambda r, k=k: r["scores"][k]) for k in FIELDS},
    }


def summarize(rows: list[dict]) -> dict:
    """MLflow에 숫자로 남길 지표 (run끼리 비교 차트에 쓰임)"""
    s = {f"acc_{k}": _rate(rows, lambda r, k=k: r["scores"][k]) for k in FIELDS}
    s["acc_all"] = _rate(rows, lambda r: r["all_ok"])                  # 세 필드 전부 맞은 비율 (대표 지표)
    s["error_rate"] = _rate(rows, lambda r: r["error"] is not None)
    s["avg_ms"] = round(sum(r["ms"] for r in rows) / len(rows))
    s["wrong_llm"] = sum(1 for r in rows for b in r["blame"].values() if b == "llm")
    s["wrong_ocr"] = sum(1 for r in rows for b in r["blame"].values() if b == "ocr")
    s["invented"] = sum(len(r.get("invented", [])) for r in rows)        # 지어낸 오답 (게이트: 늘면 탈락)
    s["corrected_beyond_ocr"] = sum(len(r.get("corrected", [])) for r in rows)   # OCR 오타를 고쳐 맞힌 수 (참고)
    for k in ("store", "total"):
        s[f"ocr_ceiling_{k}"] = _rate(rows, lambda r, k=k: r["ceiling"][k])
    s["acc_all_photo"] = _rate([r for r in rows if r["meta"].get("photo") is True], lambda r: r["all_ok"])
    s["acc_all_scan"] = _rate([r for r in rows if r["meta"].get("photo") is False], lambda r: r["all_ok"])
    return s


def breakdown(rows: list[dict]) -> dict:
    """조건별(사진/스캔, 날짜 형식 …) + 함정별 정답률 → 어떤 함정에 약한지 확인"""
    out = {}
    for gname, key in GROUPS.items():
        buckets: dict[str, list] = {}
        for r in rows:
            buckets.setdefault(str(key(r["meta"])), []).append(r)
        out[gname] = {value: _stats(group) for value, group in sorted(buckets.items())}

    # 함정은 한 장에 여러 개일 수 있음 → 함정마다 '그 함정이 들어간 장'만 모아서 계산 (v1은 함정 없음)
    trap_names = sorted({t for r in rows for t in r["meta"].get("traps", [])})
    if trap_names:
        out["traps"] = {t: _stats([r for r in rows if t in r["meta"]["traps"]]) for t in trap_names}
        clean = [r for r in rows if not r["meta"].get("traps")]
        if clean:
            out["traps"]["(함정 없음)"] = _stats(clean)
    return out


def print_report(name: str, split: str, data: str, s: dict, bd: dict, rows: list[dict]):
    print(f"\n=== {name} / {data} / {split} ({len(rows)}장) ===")
    print(f"전부 정답 {s['acc_all']:.1%} | store {s['acc_store']:.1%} | total {s['acc_total']:.1%} | date {s['acc_date']:.1%}")
    print(f"실행 실패 {s['error_rate']:.1%} | 평균 {s['avg_ms']}ms | 틀린 필드: LLM 탓 {s['wrong_llm']}개, OCR 탓 {s['wrong_ocr']}개")
    print(f"OCR 상한 store {s['ocr_ceiling_store']:.1%} | total {s['ocr_ceiling_total']:.1%}")
    print(f"지어낸 오답 {s['invented']}개 | OCR 너머 교정 {s['corrected_beyond_ocr']}개")

    print("\n[조건별 전부 정답률]  값 정답률(장수)")
    for gname, groups in bd.items():
        if gname == "traps":
            continue
        line = "  ".join(f"{v} {g['acc_all']:.0%}({g['n']})" for v, g in groups.items())
        print(f"  {gname:9} {line}")

    if "traps" in bd:
        print("\n[함정별]  전부 정답 | store / total / date")
        for t, g in bd["traps"].items():
            print(f"  {t:12} {g['n']:3}장  {g['acc_all']:5.0%} | {g['acc_store']:.0%} / {g['acc_total']:.0%} / {g['acc_date']:.0%}")

    wrong = [r for r in rows if not r["all_ok"]]
    if wrong:
        print(f"\n[틀린 예시] 최대 15개 / 전체 {len(wrong)}장")
        for r in wrong[:15]:
            if r["error"]:
                print(f"  {r['id']} 실행 실패: {r['error']}")
                continue
            traps = ",".join(r["meta"].get("traps", [])) or "-"
            for k, b in r["blame"].items():
                mark = " ⚠️지어냄" if k in r.get("invented", []) else ""
                print(f"  {r['id']} {k:5} 정답={r['label'][k]!r} 예측={r['pred'][k]!r} ({b} 탓){mark} [함정 {traps}]")


def log_to_mlflow(name, split, data_dir, program, program_path, s, bd, rows, threads, tags=None, ocr_file="ocr.jsonl",
                  model: str | None = None) -> str:
    """지표(숫자) + 조건별 분석 + 장별 예측 + 프로그램 상태를 run 하나로 기록"""
    mlflow = setup_mlflow()
    ds = dataset_info(data_dir, ocr_file)
    with mlflow.start_run(run_name=f"eval-{name}-{ds['dataset']}-{split}") as run:
        mlflow.set_tags({"stage": "eval", "program": name, "split": split, **ds, **(tags or {})})
        mlflow.log_params({
            "model": model or settings.local_llm_model,
            "program": name,
            "program_path": program_path or "(base)",
            "split": split,
            "n": len(rows),
            "threads": threads,
            **ds,
        })
        mlflow.log_metrics(s)
        mlflow.log_dict(bd, "breakdown.json")
        with tempfile.TemporaryDirectory() as tmp:
            pred_file = Path(tmp) / "predictions.jsonl"
            pred_file.write_text(
                "\n".join(json.dumps(r, ensure_ascii=False) for r in rows) + "\n", encoding="utf-8"
            )
            mlflow.log_artifact(str(pred_file))
            prog_file = Path(tmp) / "program.json"          # 프롬프트 지시문·예시 = 이 모델의 '가중치'
            program.save(str(prog_file))
            mlflow.log_artifact(str(prog_file), artifact_path="program")
        return run.info.run_id


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--data", type=Path, default=DATA_DIR, help="데이터셋 폴더 (기본 v2)")
    p.add_argument("--split", default="dev", choices=["train", "dev", "test"])
    p.add_argument("--program", help="최적화된 프로그램 JSON 경로 (생략하면 기본 프로그램 base)")
    p.add_argument("--name", default="base", help="MLflow에 남길 프로그램 이름")
    p.add_argument("--threads", type=int, default=4, help="동시 요청 수 (vLLM max-num-seqs 8보다 작게)")
    p.add_argument("--no-mlflow", action="store_true", help="기록 없이 화면 출력만")
    p.add_argument("--ocr", default="ocr.jsonl", help="OCR 텍스트셋 파일 이름 (OCR 설정 비교용)")
    args = p.parse_args()

    records = load_records(args.split, args.data, args.ocr)
    program = load_program(args.program)
    print(f"{args.name} 프로그램으로 {args.data.name}/{args.split} {len(records)}장 평가 (모델 {settings.local_llm_model})")

    rows = run_eval(program, records, args.threads)
    s, bd = summarize(rows), breakdown(rows)
    print_report(args.name, args.split, args.data.name, s, bd, rows)

    if not args.no_mlflow:
        run_id = log_to_mlflow(args.name, args.split, args.data, program, args.program, s, bd, rows, args.threads,
                               tags={"ocr_config": args.ocr}, ocr_file=args.ocr)
        print(f"\nMLflow 기록 완료: run_id={run_id}")


if __name__ == "__main__":
    main()