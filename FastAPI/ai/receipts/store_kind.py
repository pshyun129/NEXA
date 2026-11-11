"""가게 업종 분류: 상호(+품목) → 업종. 규정 검토 제6조(사용 제한 업종)의 사실로 쓴다.

1) 키워드 규칙: 상호에 업종이 드러나는 경우(노래방·컨트리클럽·김밥·스타벅스 …) → LLM 없이 바로
2) 규칙에 없으면 LLM 분류. 모르면 '모름'을 고르게 해서 억지로 끼워 맞추지 않음 (그러면 판정이 '확인 필요'로)
3) 같은 상호는 한 번만 묻는다 (프로세스 안 캐시). 영수증 수백 장도 가게 수만큼만 LLM 호출

나중에 카드사 연동이 되면 가맹점 업종 코드(MCC)를 1)보다 먼저 쓰면 된다.
"""
import re
import threading
from typing import Literal

import dspy
from pydantic import BaseModel, Field

from ai.dspy_lm import run_with_fallback

STORE_KINDS = ["카페", "음식점", "주점", "편의점·마트", "사무용품·생활용품", "교통", "숙박",
               "유흥 업종", "위락·개인 서비스", "현금성 자산", "기타", "모름"]
# 경비 규정 제6조①: 법인카드를 쓸 수 없는 업종 → 제6조 각 호
RESTRICTED = {"유흥 업종": "1.", "현금성 자산": "2.", "위락·개인 서비스": "3."}
FOOD_STORES = {"카페", "음식점", "주점"}

# (업종, 패턴) — 위에서부터 먼저 맞는 것. 제한 업종을 먼저 봐야 'OO 단란주점'이 '주점'으로 안 빠짐
RULES: list[tuple[str, re.Pattern]] = [(k, re.compile(p, re.I)) for k, p in [
    ("유흥 업종", r"단란|유흥|노래방|노래연습|노래클럽|코인노래|가라오케|룸살롱|나이트클럽|호스트바|퍼블릭"),
    ("위락·개인 서비스", r"골프|컨트리\s*클럽|(?<![A-Za-z])CC(?![A-Za-z])|안마|마사지|스파|사우나|찜질|네일|피부\s*관리|왁싱"),
    ("현금성 자산", r"상품권|기프트\s*카드|선불\s*카드|기프티콘"),
    ("카페", r"카페|커피|coffee|cafe|스타벅스|이디야|메가\s*(MGC|커피)|투썸|빽다방|컴포즈|할리스|폴\s*바셋|블루\s*보틀|공차"
            r"|베이커리|제과|파리\s*바게|뚜레쥬르|던킨|배스킨|설빙|디저트|티\s*하우스|도넛|도너츠|베이글|빵집|브레드"),
    ("주점", r"주점|포차|호프|이자카야|술집|맥주|비어|와인\s*바|펍(?![가-힣])"),
    ("음식점", r"식당|김밥|국밥|분식|한식|중식|일식|양식|한정식|반점|짜장|치킨|피자|버거|맥도날드|롯데리아|맘스터치|서브웨이"
              r"|고기|고깃|갈비|삼겹|한우|곱창|막창|횟집|회센터|초밥|스시|돈까스|돈가스|칼국수|냉면|찌개|쌀국수|마라|도시락|본죽"
              r"|죽(?![가-힣])|덮밥|라멘|우동|뷔페|레스토랑|키친|비스트로|스테이크|샤브|족발|보쌈|순대|감자탕|해장|닭갈비|백반|밥집"),
    ("편의점·마트", r"GS\s*25|(?<![A-Za-z])CU(?![A-Za-z])|세븐\s*일레븐|이마트|미니스톱|편의점|홈플러스|롯데\s*마트|마트|코스트코|트레이더스"),
    ("사무용품·생활용품", r"다이소|오피스|문구|알파|모닝글로리|핫트랙스|서점|문고|철물"),
    ("교통", r"택시|카카오\s*T|코레일|KTX|SRT|고속\s*버스|시외\s*버스|주차|하이패스|티머니|주유"),
    ("숙박", r"호텔|모텔|리조트|게스트\s*하우스|펜션|레지던스"),
]]


def rule_kind(store: str | None) -> str | None:
    s = store or ""
    return next((k for k, p in RULES if p.search(s)), None)


class StoreKind(BaseModel):
    kind: Literal["카페", "음식점", "주점", "편의점·마트", "사무용품·생활용품", "교통", "숙박",
                  "유흥 업종", "위락·개인 서비스", "현금성 자산", "기타", "모름"] = Field(
        description="가게 업종. 상호와 품목만으로 분명하지 않으면 '모름'")


class ClassifyStore(dspy.Signature):
    """영수증의 상호와 구매 품목을 보고 가게 업종을 고른다.
    상호나 품목에서 분명히 알 수 있을 때만 고르고, 짐작이 필요하면 '모름'을 고른다.
    유흥 업종: 유흥주점·단란주점·노래방 / 위락·개인 서비스: 골프장·안마·마사지 등 / 현금성 자산: 상품권·선불카드 판매"""

    store: str = dspy.InputField(desc="상호")
    items: str = dspy.InputField(desc="구매 품목 (없으면 빈 칸)")
    result: StoreKind = dspy.OutputField()


_classify = dspy.Predict(ClassifyStore)
_cache: dict[str, str] = {}
_lock = threading.Lock()


def _key(store: str) -> str:
    return re.sub(r"\s+", "", store or "")


def store_kind(store: str | None, items: list[str] | None = None) -> tuple[str, str]:
    """→ (업종, 근거: 규칙 / LLM / 캐시 / 없음)"""
    if not store:
        return "모름", "없음"
    kind = rule_kind(store)
    if kind:
        return kind, "규칙"
    key = _key(store)
    with _lock:
        if key in _cache:
            return _cache[key], "캐시"
    try:
        kind = run_with_fallback(_classify, store=store, items=", ".join(items or [])[:300]).result.kind
    except Exception:
        return "모름", "LLM 실패"          # 실패는 캐시하지 않음 (다음에 다시 물어봄)
    with _lock:
        _cache[key] = kind
    return kind, "LLM"
