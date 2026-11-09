"""규정 색인 만들기: DOCS/regulations/*.md → FastAPI/ai/rag/index/

  python -X utf8 -m mlops.build_index                  # 기본: ../DOCS/regulations (FastAPI 폴더에서 실행)
  python -X utf8 -m mlops.build_index --check          # 다시 만들지 않고, 원문이 바뀌었는지만 확인

규정이 바뀔 때만 실행 → 결과(ai/rag/index/)를 커밋 → 평가 → 배포 (README 개정 절차 3번)
"""
import argparse
import hashlib
import json
import time
from collections import Counter
from pathlib import Path

from ai.rag.index import EMBED_MODEL, INDEX_DIR, embed, save_index
from ai.rag.parse import parse_dir

DEFAULT_SRC = Path("../DOCS/regulations")


def file_hashes(src: Path) -> dict[str, str]:
    return {p.name: hashlib.sha256(p.read_bytes()).hexdigest()[:12]
            for p in sorted(src.glob("*.md")) if p.name.lower() != "readme.md"}


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--src", type=Path, default=DEFAULT_SRC)
    p.add_argument("--out", type=Path, default=INDEX_DIR)
    p.add_argument("--check", action="store_true", help="원문과 색인이 같은 버전인지만 확인")
    args = p.parse_args()

    hashes = file_hashes(args.src)
    if args.check:
        old = json.loads((args.out / "meta.json").read_text(encoding="utf-8")).get("docs", {})
        changed = sorted(n for n in hashes.keys() | old.keys() if hashes.get(n) != old.get(n))
        print("색인 최신 ✅" if not changed else f"⚠️ 원문이 바뀜 → 색인 다시 만들기 필요: {changed}")
        raise SystemExit(1 if changed else 0)

    clauses = parse_dir(args.src)            # 형식 오류(집행 누락 등)면 여기서 멈춤
    start = time.perf_counter()
    vectors = embed([c.search_text() for c in clauses])
    version = hashlib.sha256(json.dumps(hashes, sort_keys=True).encode()).hexdigest()[:12]
    meta = {
        "version": version,                  # 원문 해시들의 해시 → 판정 결과에 기록
        "model": EMBED_MODEL,
        "dim": int(vectors.shape[1]),
        "docs": hashes,
        "clauses": len(clauses),
        "built_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
    }
    save_index(clauses, vectors, meta, args.out)

    print(f"조항 {len(clauses)}개 → {args.out} (임베딩 {time.perf_counter() - start:.1f}초, 버전 {version})")
    print("규정별:", dict(Counter(c.doc_code for c in clauses)))
    print("집행별:", dict(Counter(c.enforcement for c in clauses)))


if __name__ == "__main__":
    main()