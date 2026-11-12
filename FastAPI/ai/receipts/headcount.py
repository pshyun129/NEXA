"""인원 추정: 품목 수를 그대로 세지 않고, '1인 1개' 품목만 세어 범위로 낸다.

예) 카페 커피 3잔 + 케이크 1개 → 음료 3 → 약 2~3명 (케이크는 나눠 먹을 수 있어 안 셈)
    식당 개인 메뉴 3그릇 + 공용 요리 1개 → 식사 3 → 약 2~3명
    고깃집 삼겹살 3인분 → 약 2~5명 (4명이 3인분을 시키기도 함)

쓰는 곳은 두 군데뿐이다 (1인당 한도 판정에는 쓰지 않는다)
  ① 실행 폼 인원 칸 도움말 ("음료 3잔 기준 약 2~3명")
  ② 부풀린 인원 감지: 입력 인원이 추정 최댓값 + 여유(1)보다 크면 '확인 필요'
     (인원을 늘리면 1인당 금액이 작아져 한도 위반을 피할 수 있으므로 이 방향만 잡는다)
"""
import math
import re

# 품목 종류 (추출 단계에서 LLM이 품목마다 붙임, ai/modules/receipt.py ItemKind와 같은 값)
PERSONAL_DRINK = "1인 음료"
PERSONAL_MEAL = "1인 식사"
SHARED_DISH = "공용 요리"
SIDE = "디저트·사이드"
ALCOHOL = "주류"
GOODS = "상품·소모품"
OTHER = "기타"
ITEM_KINDS = [PERSONAL_DRINK, PERSONAL_MEAL, SHARED_DISH, SIDE, ALCOHOL, GOODS, OTHER]

PORTION = re.compile(r"(\d{1,2})\s*인\s*(분|용|세트|코스)?")
INFLATION_SLACK = 1        # 추정 최댓값보다 1명 많은 것까지는 허용 (아무것도 안 시킨 사람)


def _qty(item: dict) -> int:
    try:
        return max(1, int(item.get("quantity") or 1))
    except (TypeError, ValueError):
        return 1


def estimate_headcount(items: list[dict] | None) -> dict | None:
    """품목 목록 → {"min", "max", "basis"} (셀 수 있는 품목이 없으면 None = 추정 안 함)"""
    items = items or []
    drinks = sum(_qty(i) for i in items if i.get("kind") == PERSONAL_DRINK)
    meals = sum(_qty(i) for i in items if i.get("kind") == PERSONAL_MEAL)
    portions = 0
    for i in items:
        m = PORTION.search(str(i.get("name") or ""))
        if m and i.get("kind") in (SHARED_DISH, PERSONAL_MEAL, OTHER):
            portions += int(m.group(1)) * _qty(i)

    if portions:
        return {"min": max(1, portions - 1), "max": portions + 2, "basis": f"{portions}인분"}
    if meals:
        # 한 사람이 식사를 두 그릇 시키는 일은 드묾 → 최소는 1 적게
        return {"min": max(1, meals - 1), "max": meals, "basis": f"1인 식사 {meals}개"}
    if drinks:
        # 한 사람이 음료를 두 잔 시키기도 함 → 최소는 절반
        return {"min": max(1, math.ceil(drinks / 2)), "max": drinks, "basis": f"1인 음료 {drinks}잔"}
    return None


def inflation_issue(declared: int | None, estimate: dict | None) -> str | None:
    """입력 인원이 추정보다 지나치게 많으면 사유 문구 (아니면 None)"""
    if not declared or not estimate:
        return None
    if declared > estimate["max"] + INFLATION_SLACK:
        return (f"입력 인원 {declared}명이 영수증 품목({estimate['basis']}) 기준 추정 "
                f"{estimate['min']}~{estimate['max']}명보다 많습니다")
    return None
