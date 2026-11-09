"""5-2 OCR 텍스트셋: 합성 영수증 이미지를 GPU OCR에 한 번만 돌려 텍스트로 저장.

평가(5-4)·최적화(5-5)는 같은 데이터에 LLM을 여러 번 반복해서 부르는데,
그때마다 OCR까지 다시 하면 느리고 GPU도 낭비된다. 그래서 OCR 결과를 미리 저장해 두고 재사용한다.

  python -m mlops.build_textset --data mlops/data/receipts_v2                       # 처리 안 된 것만 (이어서)
  python -m mlops.build_textset --data mlops/data/receipts_v2 --force               # 전부 다시
  python -m mlops.build_textset --data mlops/data/receipts_v2 --out ocr.no_tlo.jsonl   # OCR 설정 비교용 별도 파일

결과: {data}/{out}
  {"id", "split", "text", "confidence", "ocr_ms", "label", "meta", "ocr_config"}
"""
import argparse
import json
import time
from collections import Counter
from pathlib import Path

from ai.ocr import ocr_image


def load_jsonl(path: Path) -> list[dict]:
    if not path.exists():
        return []
    with path.open(encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def _squash(text: str) -> str:
    """띄어쓰기·줄바꿈을 모두 지움 (OCR이 띄어쓰기를 다르게 읽어도 비교되게)"""
    return "".join(text.split())


def ocr_ceiling(rec: dict) -> dict:
    """정답이 OCR 텍스트 안에 '보이기는 하는지' 확인 → 추출 정확도의 상한선"""
    text, label = _squash(rec["text"]), rec["label"]
    return {
        "store": _squash(label["store"]) in text,
        "total": f"{label['total']:,}" in text or str(label["total"]) in text,
    }


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--data", type=Path, default=Path("mlops/data/receipts"))
    p.add_argument("--out", default="ocr.jsonl", help="저장할 파일 이름 (OCR 설정 비교 때 바꿔서 사용)")
    p.add_argument("--force", action="store_true", help="이미 처리한 것도 전부 다시")
    args = p.parse_args()

    labels = load_jsonl(args.data / "labels.jsonl")
    if not labels:
        raise SystemExit("labels.jsonl이 없습니다. 먼저 python mlops/gen_receipts.py 를 실행하세요")

    out_path = args.data / args.out
    done = {} if args.force else {r["id"]: r for r in load_jsonl(out_path)}
    todo = [r for r in labels if r["id"] not in done]
    print(f"전체 {len(labels)}장 / 이미 처리 {len(done)}장 / 이번에 처리 {len(todo)}장 → {out_path}")

    # 한 장 끝날 때마다 바로 파일에 씀 → 중간에 끊겨도 다음 실행 때 이어서 처리
    with out_path.open("w" if args.force else "a", encoding="utf-8") as f:
        for i, row in enumerate(todo, 1):
            start = time.perf_counter()
            result = ocr_image((args.data / row["file"]).read_bytes())   # GPU 서버 OCR 호출
            scores = [item.score for item in result.items]
            rec = {
                "id": row["id"],
                "split": row["split"],
                "text": result.text,
                "confidence": round(sum(scores) / len(scores), 4) if scores else 0.0,
                "ocr_ms": round((time.perf_counter() - start) * 1000),
                "label": row["label"],
                "meta": row["meta"],
                "ocr_config": result.config,      # 어떤 OCR 설정으로 읽었는지 (데이터 계보)
            }
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")
            f.flush()
            done[row["id"]] = rec
            if i % 10 == 0 or i == len(todo):
                print(f"  {i}/{len(todo)} 처리")

    # 요약: 사진/스캔별 신뢰도, 속도, OCR 상한
    records = [done[r["id"]] for r in labels if r["id"] in done]
    configs = Counter(json.dumps(r.get("ocr_config") or {}, sort_keys=True) for r in records)
    print(f"OCR 설정: {dict(configs)}")   # 한 파일에 설정이 섞였으면 2개 이상 나옴 → 다시 만들어야 함
    for name, group in [("스캔", [r for r in records if not r["meta"]["photo"]]),
                        ("사진", [r for r in records if r["meta"]["photo"]])]:
        if group:
            conf = sum(r["confidence"] for r in group) / len(group)
            ms = sum(r["ocr_ms"] for r in group) / len(group)
            store = sum(ocr_ceiling(r)["store"] for r in group) / len(group)
            print(f"{name} {len(group)}장: 평균 신뢰도 {conf:.4f}, 평균 {ms:.0f}ms, store 상한 {store:.1%}")

    ceiling = [ocr_ceiling(r) for r in records]
    for key in ("store", "total"):
        rate = sum(c[key] for c in ceiling) / len(ceiling)
        print(f"OCR 상한 {key}: {rate:.1%} (정답 글자가 OCR 텍스트에 그대로 있는 비율)")
    print(f"저장: {out_path}")


if __name__ == "__main__":
    main()