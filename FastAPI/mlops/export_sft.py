"""QLoRA 학습 데이터 만들기 (PC에서 실행): OCR 텍스트셋 + 정답 → 대화(messages) 형식 JSONL.

핵심: 프롬프트를 직접 쓰지 않고 DSPy가 실제 서빙에서 보내는 프롬프트를 그대로 만든다
  (ChatAdapter.format_finetune_data = 추론 때 adapter.format()과 같은 system/user + 정답 assistant)
  → 학습한 모델이 서빙 프롬프트와 정확히 같은 형식을 보게 됨. 형식이 다르면 학습 효과가 서빙에서 안 나옴.
  지시문·예시도 지금 서버가 쓰는 프로그램(챔피언 파일, 없으면 base) 것을 그대로 사용.

평가셋과 섞이지 않게: 학습에는 receipts_v2의 train + receipts_v2_train(학습 전용, seed 7) 만 씀.
dev/test는 절대 넣지 않음 (아침 평가·승격 게이트에 그대로 사용)

실행: python -X utf8 -m mlops.export_sft
"""
import argparse
import hashlib
import json
import random
from pathlib import Path

import dspy

from ai.modules.receipt import RECEIPT_VERSION, Receipt, _extractor
from mlops.common import load_records

SOURCES = [                                     # (폴더, 쓸 split) — split None = 전부
    (Path("mlops/data/receipts_v2"), "train"),
    (Path("mlops/data/receipts_v2_train"), None),
]
OUT = Path("mlops/data/sft/receipt_sft.jsonl")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--ocr", default="ocr.jsonl", help="OCR 텍스트셋 파일 이름 (서빙과 같은 OCR 설정이어야 함)")
    p.add_argument("--eval-ratio", type=float, default=0.05, help="학습 중 손실 확인용으로 떼어둘 비율")
    p.add_argument("--seed", type=int, default=42)
    args = p.parse_args()

    adapter = dspy.ChatAdapter()                 # DSPy 기본 어댑터 (서빙과 같음)
    predictor = _extractor.extract               # 서버가 쓰는 Predict (지시문·데모 포함)

    rows, used, configs = [], [], set()
    for folder, split in SOURCES:
        records = load_records(split, folder, args.ocr)
        used.append({"data": str(folder), "split": split or "all", "n": len(records),
                     "ocr_sha": hashlib.sha256((folder / args.ocr).read_bytes()).hexdigest()[:12]})
        configs |= {json.dumps(r.get("ocr_config", {}), sort_keys=True) for r in records}
        for r in records:
            data = adapter.format_finetune_data(
                predictor.signature, predictor.demos,
                inputs={"text": r["text"]},
                outputs={"receipt": Receipt(**r["label"])},
            )
            rows.append({"id": f"{folder.name}/{r['id']}", "messages": data["messages"]})

    # 두 폴더 모두 서빙과 같은 OCR 설정이어야 함 (설정이 다르면 학습 입력과 실제 입력이 달라짐)
    if len(configs) != 1:
        raise SystemExit(f"OCR 설정이 섞여 있습니다 {configs} → 같은 설정으로 텍스트셋을 다시 만드세요")

    random.Random(args.seed).shuffle(rows)
    n_eval = max(1, int(len(rows) * args.eval_ratio))
    for i, row in enumerate(rows):
        row["split"] = "eval" if i < n_eval else "train"

    OUT.parent.mkdir(parents=True, exist_ok=True)
    with OUT.open("w", encoding="utf-8") as f:
        for row in rows:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")
    meta = {"program": RECEIPT_VERSION, "sources": used, "n_train": len(rows) - n_eval, "n_eval": n_eval,
            "ocr_config": json.loads(next(iter(configs))), "sha256": hashlib.sha256(OUT.read_bytes()).hexdigest()[:12]}
    OUT.with_suffix(".meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")

    sample = rows[0]["messages"]
    chars = sorted(sum(len(m["content"]) for m in r["messages"]) for r in rows)
    print(f"저장: {OUT} (학습 {meta['n_train']} / 손실 확인 {n_eval}) | 프로그램 {RECEIPT_VERSION} | OCR 설정 {meta['ocr_config']}")
    print(f"글자 수: 중앙값 {chars[len(chars) // 2]} / 최대 {chars[-1]}  (한국어는 대략 글자 수 ≈ 토큰 수)")
    print("\n--- 예시 user 메시지 끝부분 ---\n" + sample[1]["content"][-300:])
    print("\n--- 예시 정답(assistant) ---\n" + sample[2]["content"])


if __name__ == "__main__":
    main()