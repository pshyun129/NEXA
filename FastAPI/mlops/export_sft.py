"""QLoRA 학습 데이터 만들기: OCR 텍스트셋 + 정답 → 대화(messages) 형식 JSONL.

핵심: 프롬프트를 직접 쓰지 않고 DSPy가 실제 서빙에서 보내는 프롬프트를 그대로 만든다
  (ChatAdapter.format_finetune_data = 추론 때 adapter.format()과 같은 system/user + 정답 assistant)
  → 학습한 모델이 서빙 프롬프트와 정확히 같은 형식을 보게 됨. 형식이 다르면 학습 효과가 서빙에서 안 나옴.
  지시문·예시도 지금 서빙 중인 프로그램 것을 그대로 사용 (밤 작업은 MLflow champion, 수동 실행은 Git 파일·base)
  → 밤마다 다시 만들기 때문에 스키마(예: 품목 items 추가)나 champion 프롬프트가 바뀌면 학습 데이터도 같이 바뀜

평가셋과 섞이지 않게: 합성은 receipts_v2의 train + receipts_v2_train(학습 전용, seed 7)만, 골든셋은 학습용(train) 몫만.
dev/test·골든 평가용은 절대 넣지 않음 (승격 게이트에 그대로 사용)

라벨
  합성  store/total/date = 정답, items = 생성기 정답 (labels.jsonl의 meta.items)
  골든  store/total/date = 사람이 확정한 값, items = 지금 서빙 모델의 추출 결과 (사람은 품목을 확정하지 않음)
        → 품목을 빈 목록으로 가르치면 '품목을 빼고 답하기'를 배우니까 모델 출력으로 채움

v2 (2026-11-09) — v1 어댑터가 '없는 상호를 지어내는' 문제(지어낸 오답 0 → 4)를 고치기 위한 두 가지
  ① 근거 있는 라벨만: 정답 상호·금액이 OCR 텍스트에 보이는 영수증만 학습
     (합성: 그대로 보여야 함 / 골든: 한 글자 오타까지 허용 — 사람이 고친 진짜 OCR 오타 교정은 배울 가치가 있음)
  ② 일부 브랜드는 학습에서 아예 뺌(HOLDOUT): dev/test의 이 브랜드 영수증 = '처음 보는 상호'

실행: python -X utf8 -m mlops.export_sft                  # 합성만 (근거 있는 라벨 + 브랜드 4개 제외)
      python -X utf8 -m mlops.export_sft --keep-ungrounded --holdout ""   # v1과 같은 방식
      (밤 작업: python -m mlops.nightly sft 가 골든셋 학습용까지 넣어 만듦)
"""
import argparse
import hashlib
import json
import random
from collections import Counter
from pathlib import Path

import dspy

from ai.modules.receipt import Receipt, current_extractor, receipt_version
from mlops.common import load_records, squash

SOURCES = [                                     # (폴더, 쓸 split) — split None = 전부
    (Path("mlops/data/receipts_v2"), "train"),
    (Path("mlops/data/receipts_v2_train"), None),
]
OUT = Path("mlops/data/sft/receipt_sft.jsonl")
# 업종마다 1개씩 학습에서 뺄 브랜드 (dev+test에 약 36장 → '처음 보는 상호' 평가 묶음)
HOLDOUT = "달빛커피,푸른마트,세븐일레븐,한솥도시락"


def grounded(r: dict, allow_typo: bool = False) -> bool:
    """정답 상호·금액이 OCR 텍스트에 보이는지 (띄어쓰기·쉼표 무시). allow_typo: 상호 한 글자 오타까지 허용"""
    from mlops.optimize import store_in_text

    text = squash(r["text"])
    total_ok = str(r["label"]["total"]) in text.replace(",", "")
    store = store_in_text(r["label"]["store"], r["text"])
    return total_ok and (store == "exact" or (allow_typo and store == "near"))


def label_items(folder: Path) -> dict[str, list]:
    """합성 영수증의 품목 정답 (gen_receipts가 labels.jsonl meta.items에 기록, 2026-11-11부터)"""
    path = folder / "labels.jsonl"
    rows = [json.loads(x) for x in path.read_text(encoding="utf-8").splitlines() if x.strip()] if path.exists() else []
    items = {r["id"]: r["meta"]["items"] for r in rows if "items" in r.get("meta", {})}
    if not items:
        raise SystemExit(f"{path}에 품목 정답(meta.items)이 없습니다 → 같은 seed로 gen_receipts를 다시 돌려 "
                         f"labels.jsonl만 바꾸세요 (이미지·라벨은 그대로 같음)")
    return items


def build(program=None, ocr: str = "ocr.jsonl", holdout: list[str] | None = None, keep_ungrounded: bool = False,
          golden: list[dict] | None = None, golden_items=None, eval_ratio: float = 0.05, seed: int = 42) -> tuple[list, dict]:
    """학습 행 + 메타. program: 프롬프트 형식을 가져올 프로그램 (생략하면 지금 서빙 중인 것)
    golden: 골든셋 학습용 행, golden_items: 텍스트 → 품목 목록 (지금 서빙 모델로 추출)"""
    holdout = HOLDOUT.split(",") if holdout is None else holdout
    adapter = dspy.ChatAdapter()                 # DSPy 기본 어댑터 (서빙과 같음)
    predictor = (program or current_extractor()).extract   # 서빙 Predict (지시문·데모 포함)

    def to_row(rid: str, text: str, label: dict, items: list) -> dict:
        data = adapter.format_finetune_data(predictor.signature, predictor.demos, inputs={"text": text},
                                            outputs={"receipt": Receipt(**label, items=items)})
        return {"id": rid, "messages": data["messages"]}

    rows, used, configs, skipped = [], [], set(), Counter()
    for folder, split in SOURCES:
        records = load_records(split, folder, ocr)
        items = label_items(folder)
        used.append({"data": folder.as_posix(), "split": split or "all", "n": len(records),
                     "ocr_sha": hashlib.sha256((folder / ocr).read_bytes()).hexdigest()[:12]})
        configs |= {json.dumps(r.get("ocr_config", {}), sort_keys=True) for r in records}
        for r in records:
            if any(squash(r["label"]["store"]).startswith(squash(b)) for b in holdout):
                skipped["holdout_brand"] += 1
                continue
            if not keep_ungrounded and not grounded(r):
                skipped["ungrounded"] += 1
                continue
            if r["id"] not in items:
                skipped["no_items"] += 1
                continue
            rows.append(to_row(f"{folder.name}/{r['id']}", r["text"], r["label"], items[r["id"]]))

    # 두 폴더 모두 서빙과 같은 OCR 설정이어야 함 (설정이 다르면 학습 입력과 실제 입력이 달라짐)
    if len(configs) != 1:
        raise SystemExit(f"OCR 설정이 섞여 있습니다 {configs} → 같은 설정으로 텍스트셋을 다시 만드세요")

    n_golden = 0
    for r in golden or []:
        if not grounded(r, allow_typo=True):
            skipped["golden_ungrounded"] += 1
            continue
        try:
            items = golden_items(r["text"]) if golden_items else []
        except Exception:
            skipped["golden_items_failed"] += 1
            continue
        rows.append(to_row(f"golden/{r['id']}", r["text"], r["label"], items))
        n_golden += 1

    random.Random(seed).shuffle(rows)
    n_eval = max(1, int(len(rows) * eval_ratio))
    for i, row in enumerate(rows):
        row["split"] = "eval" if i < n_eval else "train"
    body = "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows)
    meta = {"program": receipt_version() if program is None else getattr(program, "label", "?"),
            "sources": used, "n_train": len(rows) - n_eval, "n_eval": n_eval, "n_golden": n_golden,
            "ocr_config": json.loads(next(iter(configs))), "holdout_brands": holdout,
            "grounded_only": not keep_ungrounded, "skipped": dict(skipped),
            "sha256": hashlib.sha256(body.encode("utf-8")).hexdigest()[:12]}
    return rows, meta


def write(rows: list[dict], meta: dict, out: Path):
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8") as f:
        for row in rows:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")
    out.with_suffix(".meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--ocr", default="ocr.jsonl", help="OCR 텍스트셋 파일 이름 (서빙과 같은 OCR 설정이어야 함)")
    p.add_argument("--eval-ratio", type=float, default=0.05, help="학습 중 손실 확인용으로 떼어둘 비율")
    p.add_argument("--seed", type=int, default=42)
    p.add_argument("--holdout", default=HOLDOUT, help="학습에서 뺄 브랜드 (쉼표 구분, 빈 문자열이면 안 뺌)")
    p.add_argument("--keep-ungrounded", action="store_true", help="정답이 OCR 텍스트에 없는 영수증도 학습 (v1 방식)")
    p.add_argument("--out", type=Path, default=OUT)
    args = p.parse_args()

    rows, meta = build(ocr=args.ocr, holdout=[b.strip() for b in args.holdout.split(",") if b.strip()],
                       keep_ungrounded=args.keep_ungrounded, eval_ratio=args.eval_ratio, seed=args.seed)
    write(rows, meta, args.out)
    chars = sorted(sum(len(m["content"]) for m in r["messages"]) for r in rows)
    print(f"저장: {args.out} (학습 {meta['n_train']} / 손실 확인 {meta['n_eval']}) | 프로그램 {meta['program']} | "
          f"OCR 설정 {meta['ocr_config']}")
    print(f"뺀 영수증: {meta['skipped']} | 제외 브랜드 {meta['holdout_brands']} | sha {meta['sha256']}")
    print(f"글자 수: 중앙값 {chars[len(chars) // 2]} / 최대 {chars[-1]}  (한국어는 대략 글자 수 ≈ 토큰 수)")
    print("\n--- 예시 정답(assistant) ---\n" + rows[0]["messages"][2]["content"])


if __name__ == "__main__":
    main()
