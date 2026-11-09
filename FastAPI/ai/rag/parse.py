"""사내 규정 Markdown → 조항 목록.

DOCS/regulations/*.md 형식 (README의 작성 규칙):
  front matter: 규정명, 규정코드, 소관부서, 시행일, 집행(조항별 집행 방식), 목적(선택: 특정 사용 목적에만 적용되는 조항),
               수치(선택: 코드가 직접 비교하는 숫자 기준)
  ## 제N장 제목 / ### 제N조 (제목) / ①②③ 항
조(條) 단위로 쪼갠다 → 판정 근거를 "EXP-8"(경비 규정 제8조)처럼 정확히 인용·검증할 수 있게.
"""
import re
from dataclasses import dataclass, field
from pathlib import Path

import yaml

ARTICLE = re.compile(r"^### 제(\d+)조\s*\((.+?)\)\s*$")   # ### 제5조 (1인 식대 한도)
CHAPTER = re.compile(r"^## (.+)$")                        # ## 제2장 법인카드
FRONT = re.compile(r"^---\n(.*?)\n---\n(.*)$", re.S)       # 맨 위 front matter
ENFORCEMENTS = {"시스템", "설정", "AI", "사람", "참고"}
NUMERIC_KINDS = {"최대", "기준"}   # 최대: 넘으면 위반 / 기준: 넘는지 여부만 사실로 계산 (예: 3만 원 넘으면 적격증빙 필수)


@dataclass
class Clause:
    id: str                 # EXP-8 (규정코드-조번호) → 인용·검증에 쓰는 키
    doc_code: str           # EXP / TRV / LEV
    doc_name: str           # 경비 및 법인카드 사용 규정
    chapter: str            # 제3장 항목별 사용 기준
    article: int            # 8
    title: str              # 식대
    body: str               # 조항 본문 원문 (인용 문장 검증의 기준)
    enforcement: str        # 시스템 / 설정 / AI / 사람 / 참고
    enforcement_note: str   # 시스템·설정이면 어디서 집행하는지
    effective_date: str     # 시행일
    source: str             # 원문 파일 이름
    purposes: list[str] = field(default_factory=list)   # 적용되는 사용 목적 (비어 있으면 모든 경비에 적용)
    numeric: list[dict] = field(default_factory=list)   # 숫자 기준 [{"사실", "최대"|"기준", "목적"?}] → 코드가 비교

    @property
    def label(self) -> str:
        """사람이 읽는 이름: 경비 및 법인카드 사용 규정 제8조(식대)"""
        return f"{self.doc_name} 제{self.article}조({self.title})"

    def search_text(self) -> str:
        """검색용 텍스트: 규정명 > 장 > 조 제목을 본문 앞에 붙여 문맥까지 검색되게"""
        return f"{self.doc_name} > {self.chapter} > 제{self.article}조 ({self.title})\n{self.body}"


def _check_numeric(fname: str, key: str, rule: dict, body: str, purposes: list) -> dict:
    """수치 기준 한 줄 검사: 형식 + 숫자가 본문에 그대로 있는지 (front matter와 본문이 어긋나는 것 방지)"""
    kinds = NUMERIC_KINDS & set(rule)
    if not rule.get("사실") or len(kinds) != 1:
        raise ValueError(f"{fname}: {key} 수치 기준은 '사실'과 '최대'/'기준' 중 하나가 필요합니다: {rule}")
    value = rule[kinds.pop()]
    if not isinstance(value, int) or (f"{value:,}" not in body and str(value) not in body):
        raise ValueError(f"{fname}: {key} 수치 {value}가 조항 본문에 없습니다")
    if rule.get("목적") and rule["목적"] not in purposes:
        raise ValueError(f"{fname}: {key} 수치 기준의 목적 '{rule['목적']}'이 목적 표에 없습니다")
    return dict(rule)


def parse_file(path: Path) -> list[Clause]:
    m = FRONT.match(path.read_text(encoding="utf-8"))
    if not m:
        raise ValueError(f"{path.name}: front matter(---)가 없습니다")
    meta, body = yaml.safe_load(m.group(1)), m.group(2)
    enforcement = meta.get("집행") or {}
    purposes = meta.get("목적") or {}
    numeric = meta.get("수치") or {}

    clauses: list[Clause] = []
    chapter, current, lines = "", None, []

    def flush():
        """모아 둔 줄을 조항 하나로 확정"""
        if current is None:
            return
        no, title = current
        key = f"제{no}조"
        if key not in enforcement:          # 집행 방식이 없는 조항은 만들지 않음 (분류 누락 방지)
            raise ValueError(f"{path.name}: {key}의 집행 방식이 front matter에 없습니다")
        kind, _, note = str(enforcement[key]).partition(" ")
        if kind not in ENFORCEMENTS:
            raise ValueError(f"{path.name}: {key}의 집행 방식 '{kind}'는 {sorted(ENFORCEMENTS)} 중 하나여야 합니다")
        text = "\n".join(lines).strip()
        rules = [_check_numeric(path.name, key, r, text, purposes.get(key, [])) for r in numeric.get(key, [])]
        clauses.append(Clause(
            id=f"{meta['규정코드']}-{no}", doc_code=meta["규정코드"], doc_name=meta["규정명"],
            chapter=chapter, article=no, title=title, body=text,
            enforcement=kind, enforcement_note=note.strip(),
            effective_date=str(meta["시행일"]), source=path.name,
            purposes=[str(x) for x in purposes.get(key, [])], numeric=rules,
        ))

    for line in body.splitlines():
        if a := ARTICLE.match(line):
            flush()
            current, lines = (int(a.group(1)), a.group(2).strip()), []
        elif c := CHAPTER.match(line):
            flush()
            current, chapter = None, c.group(1).strip()
        elif current is not None and not line.startswith("# "):
            lines.append(line)
    flush()

    # 반대 방향 검사: 집행 표에는 있는데 본문에 없는 조항 (조 번호를 바꾸고 표를 안 고친 경우)
    missing = set(enforcement) - {f"제{c.article}조" for c in clauses}
    if missing:
        raise ValueError(f"{path.name}: 집행 표에만 있고 본문에 없는 조항 {sorted(missing)}")
    stray = (set(purposes) | set(numeric)) - {f"제{c.article}조" for c in clauses}
    if stray:
        raise ValueError(f"{path.name}: 목적·수치 표에만 있고 본문에 없는 조항 {sorted(stray)}")
    return clauses


def parse_dir(src: Path) -> list[Clause]:
    """폴더 안의 규정 전부 (README.md 제외)"""
    files = sorted(p for p in Path(src).glob("*.md") if p.name.lower() != "readme.md")
    if not files:
        raise ValueError(f"{src}에 규정 파일이 없습니다")
    return [c for f in files for c in parse_file(f)]