"""합성 영수증 데이터셋 생성기 (MLOps 평가·최적화용).

정답(store/total/date)을 알고 있는 영수증 이미지를 만들어서 train / dev / test 로 나눠 저장한다.
실제 사람 라벨(run_feedback)이 쌓이기 전까지 쓰는 대체 데이터.

  python mlops/gen_receipts.py                                                  # v2(hard) 300장 → mlops/data/receipts_v2
  python mlops/gen_receipts.py --level easy --n 120 --out mlops/data/receipts   # v1 그대로 재현

난이도
  easy (v1): 날짜 형식 5종, 합계 라벨 6종, 할인·부가세 줄, 매장명 위치 3종, 사진 효과
  hard (v2): easy + 헷갈리는 숫자·날짜·상호를 일부러 섞은 함정 (meta.traps에 기록)
    points       포인트 사용 → '합계'와 '실제 결제 금액'이 다름 (정답은 실제 결제 금액)
    cash_change  현금 받은금액·거스름돈 → 결제 금액과 다른 금액
    reward_left  잔여 포인트 → 금액처럼 생긴 다른 숫자
    unit_price   품목마다 단가 표시 → 숫자가 더 많아짐
    split_total  결제 금액 라벨과 숫자가 다른 줄에 있음
    print_date   재발행 출력일 → 거래일보다 먼저 나오는 다른 날짜
    refund_date  교환·환불 기한 날짜
    coupon_date  쿠폰 유효기간 날짜
    corp_name    법인명(주식회사 …) 줄 → 상호처럼 보이는 다른 이름
    split_store  브랜드와 지점명이 다른 줄에 있음

결과: {out}/{train,dev,test}/r0001.jpg ..., {out}/labels.jsonl
"""
import argparse
import json
import random
from collections import Counter
from datetime import date, timedelta
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

# ---------------------------------------------------------------- 폰트
# 윈도우(맑은 고딕) → 리눅스(Noto CJK) 순서로 찾음
FONT_CANDIDATES = [
    ("C:/Windows/Fonts/malgun.ttf", "C:/Windows/Fonts/malgunbd.ttf", 0),
    ("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc", "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc", 1),
]


def _find_fonts():
    for regular, bold, index in FONT_CANDIDATES:
        if Path(regular).exists():
            return regular, (bold if Path(bold).exists() else regular), index
    raise SystemExit("한글 폰트를 찾지 못했습니다. FONT_CANDIDATES에 경로를 추가하세요.")


REGULAR, BOLD, FONT_INDEX = _find_fonts()
_font_cache: dict = {}


def font(size: int, bold: bool = False):
    key = (size, bold)
    if key not in _font_cache:
        _font_cache[key] = ImageFont.truetype(BOLD if bold else REGULAR, size, index=FONT_INDEX)
    return _font_cache[key]


# ---------------------------------------------------------------- 재료
BRANDS = {
    "cafe": ["카페 온도", "커피나무", "블루빈", "달빛커피", "모닝브루", "카페 소담"],
    "mart": ["푸른마트", "이웃마트", "한빛마트", "새벽시장", "바른마트"],
    "conv": ["GS25", "CU", "세븐일레븐", "이마트24"],
    "food": ["김밥천국", "한솥도시락", "본죽", "명동칼국수", "고향순대국", "하나로식당"],
}
BRANCHES = ["역삼점", "선릉점", "강남역점", "삼성점", "판교점", "구미인동점", "광주첨단점", "부산서면점", ""]
ITEMS = {
    "cafe": [("아메리카노(ICE)", 4500), ("카페라떼", 5000), ("바닐라라떼", 5500), ("바닐라 스콘", 3800), ("치즈케이크", 6500), ("콜드브루", 5000)],
    "mart": [("생수 2L", 1900), ("컵라면 소컵", 1450), ("바나나 1송이", 4990), ("흰우유 900ml", 2980), ("계란 15구", 7330), ("두부 300g", 1800)],
    "conv": [("삼각김밥", 1500), ("바나나우유", 1700), ("컵커피", 2500), ("도시락", 4900), ("생수 500ml", 950), ("과자", 1800)],
    "food": [("김치찌개", 9000), ("제육볶음", 10000), ("칼국수", 9500), ("순대국", 10000), ("공기밥", 1000), ("만두", 6000)],
}
DATE_FORMATS = ["%Y-%m-%d", "%Y/%m/%d", "%Y.%m.%d", "%y-%m-%d", "%Y년 %m월 %d일"]
TOTAL_LABELS = ["합계", "총액", "결제금액", "받을금액", "총 결제금액", "합계금액"]
# hard 전용 재료
CORPS = ["(주)한빛유통", "㈜푸른리테일", "주식회사 다온푸드", "(주)씨앤에프", "㈜새봄에프앤비"]
OWNERS = ["김민수", "이서연", "박지훈", "최유진"]
PRE_POINT_LABELS = ["합계", "총액", "합계금액"]                       # 포인트 차감 '전' 금액 라벨 (함정)
PAID_LABELS = ["결제금액", "받을금액", "실결제금액", "총 결제금액"]     # 포인트 차감 '후' 실제 결제 금액 라벨
W = 576


def won(n: int, style: str) -> str:
    s = f"{n:,}"
    return {"plain": s, "won": s + "원", "symbol": "₩" + s}[style]


def _hard_plan(rng: random.Random, branch: str) -> dict:
    """hard 함정을 미리 한꺼번에 뽑아 둠.
    easy일 때는 이 함수를 아예 안 부르므로 easy의 난수 순서가 v1과 똑같이 유지됨 (seed 42 → v1 재현)"""
    return {
        "unit_price": rng.random() < 0.5,
        "corp_name": rng.random() < 0.4,
        "corp_top": rng.random() < 0.5,              # True면 법인명이 상호보다 위 (더 헷갈림)
        "corp": rng.choice(CORPS),
        "owner": rng.choice(OWNERS),
        "split_store": bool(branch) and rng.random() < 0.3,
        "print_date": rng.random() < 0.35,
        "print_gap": rng.randint(1, 20),             # 거래일 며칠 뒤에 재발행했는지
        "print_hhmm": f"{rng.randint(8, 21):02d}:{rng.randint(0, 59):02d}",
        "refund_date": rng.random() < 0.4,
        "refund_fmt": rng.choice(DATE_FORMATS),
        "coupon_date": rng.random() < 0.3,
        "coupon_gap": rng.randint(30, 120),
        "coupon_fmt": rng.choice(DATE_FORMATS),
        "split_total": rng.random() < 0.25,
        "pre_label": rng.choice(PRE_POINT_LABELS),
        "paid_label": rng.choice(PAID_LABELS),
        "extra_cash": rng.choice([0, 0, 10000, 40000]),
        "reward_left": rng.randrange(1000, 60000, 100),
        "points": rng.choice([0] * 5 + [1000, 2000, 3000, 5000]),   # 약 44% (포인트 안 쓴 영수증이 더 많게)
    }


# ---------------------------------------------------------------- 영수증 내용
def make_receipt(rng: random.Random, hard: bool = False) -> tuple[list, dict, dict]:
    kind = rng.choice(list(BRANDS))
    brand, branch = rng.choice(BRANDS[kind]), rng.choice(BRANCHES)
    store = f"{brand} {branch}".strip()
    day = date(2026, 9, 1) + timedelta(days=rng.randint(0, 63))
    hhmm = f"{rng.randint(7, 22):02d}:{rng.randint(0, 59):02d}"
    date_fmt = rng.choice(DATE_FORMATS)
    money = rng.choice(["plain", "plain", "won", "symbol"])

    items = []
    for name, price in rng.sample(ITEMS[kind], rng.randint(2, 5)):
        qty = rng.choice([1, 1, 1, 2, 3])
        items.append((name, qty, price * qty))
    subtotal = sum(amount for _, _, amount in items)
    # 할인 줄: 품목 합 ≠ 결제 금액이 되도록 (모델이 품목을 더하지 말고 '합계'를 읽게)
    discount = rng.choice([0, 0, 0, 500, 1000, round(subtotal * 0.1, -2)])
    total = int(subtotal - discount)
    supply = round(total / 1.1)
    vat = total - supply

    t = _hard_plan(rng, branch) if hard else {}     # easy면 빈 dict → 아래 t.get(...)이 전부 None(=거짓)
    points = t.get("points", 0)
    if points and total - points < 1000:            # 포인트가 결제액을 다 덮으면 함정 취소
        points = 0
    paid = total - points                           # 정답 금액 = 실제 결제 금액
    traps: list[str] = []

    rows = []
    # 매장명 위치를 섞음: 첫 줄 / "매장명:" 줄 / [영수증] 머리글 아래
    header_style = rng.choice(["top", "top", "labeled", "titled"])
    if t.get("corp_name") and t["corp_top"]:
        rows.append(("center", t["corp"], 20))      # 법인명이 맨 위에 → 상호로 착각하기 쉬움
    if header_style == "titled":
        rows.append(("center", "[ 영 수 증 ]", 24, True))
    if header_style == "labeled":
        rows.append(("center", "영수증", 28, True))
        rows.append(("row", f"매장명: {store}", "", "", 20))
    elif t.get("split_store"):
        rows.append(("center", brand, 30, True))    # 브랜드만 큰 글씨
        rows.append(("center", branch, 22))         # 지점명은 다음 줄 작은 글씨
        traps.append("split_store")
    else:
        rows.append(("center", store, 30, True))
    rows.append(("center", f"사업자번호 {rng.randint(100,999)}-{rng.randint(10,99)}-{rng.randint(10000,99999)}", 18))
    if t.get("corp_name"):
        if not t["corp_top"]:
            rows.append(("center", f"{t['corp']}  대표 {t['owner']}", 18))
        traps.append("corp_name")
    rows.append(("center", rng.choice(["서울특별시 강남구 테헤란로 000", "경기도 성남시 분당구 판교로 000", "경북 구미시 인동가산로 000", "부산 부산진구 중앙대로 000"]), 18))
    rows.append(("center", f"TEL 0{rng.randint(2, 70)}-000-{rng.randint(1000, 9999)}", 18))
    rows.append(("line",))
    if t.get("print_date"):
        # 재발행 출력일: 거래일보다 '먼저' 나오는 다른 날짜
        pday = day + timedelta(days=t["print_gap"])
        rows.append(("row", f"[재발행] 출력 {pday.strftime(date_fmt)} {t['print_hhmm']}", "", "", 18))
        traps.append("print_date")
    rows.append(("row", f"{day.strftime(date_fmt)} {hhmm}", "", f"POS {rng.randint(1, 9):02d}", 19))
    rows.append(("line",))
    rows.append(("row", "품명", "수량", "금액"))
    for name, qty, amount in items:
        left = f"{name} @{amount // qty:,}" if t.get("unit_price") else name   # 단가 표시 → 숫자가 늘어남
        rows.append(("row", left, str(qty), won(amount, money)))
    if t.get("unit_price"):
        traps.append("unit_price")
    rows.append(("line",))
    if discount:
        rows.append(("row", "할인", "", "-" + won(int(discount), money)))
    rows.append(("row", "과세물품가액", "", won(supply, money)))
    rows.append(("row", "부가세", "", won(vat, money)))
    rows.append(("line",))

    total_label = rng.choice(TOTAL_LABELS)          # easy와 같은 위치의 난수 호출 (순서 유지)
    if points:
        # 포인트 차감 '전' 합계를 큰 글씨로 → 모델이 이걸 고르면 오답
        rows.append(("row", t["pre_label"], "", won(total, money), 26, True))
        rows.append(("row", "포인트 사용", "", "-" + won(points, money)))
        total_label = t["paid_label"]
        traps.append("points")
    if t.get("split_total"):
        rows.append(("row", total_label, "", "", 26, True))        # 라벨만
        rows.append(("row", "", "", won(paid, money), 26, True))   # 숫자는 다음 줄
        traps.append("split_total")
    else:
        rows.append(("row", total_label, "", won(paid, money), 26, True))

    pay = rng.choice(["신용카드", "현금", "카카오페이"])
    if hard and pay == "현금":
        received = (paid // 10000 + 1) * 10000 + t["extra_cash"]   # 결제액보다 큰 '받은 돈'
        rows.append(("row", "받은금액(현금)", "", won(received, money)))
        rows.append(("row", "거스름돈", "", won(received - paid, money)))
        traps.append("cash_change")
    else:
        rows.append(("row", pay, "", won(paid, money)))
    if pay == "신용카드":
        rows.append(("row", "승인번호", "", f"{rng.randint(10**7, 10**8 - 1)}", 19))
    if points:
        rows.append(("row", "잔여 포인트", "", f"{t['reward_left']:,}"))  # 금액처럼 생긴 다른 숫자
        traps.append("reward_left")
    rows.append(("line",))
    rows.append(("center", rng.choice(["감사합니다", "또 방문해 주세요", "교환/환불은 7일 이내 영수증 지참"]), 20))
    if t.get("refund_date"):
        rows.append(("center", f"교환/환불 기한 {(day + timedelta(days=14)).strftime(t['refund_fmt'])}까지", 18))
        traps.append("refund_date")
    if t.get("coupon_date"):
        rows.append(("center", "다음 방문 10% 할인쿠폰", 18))
        rows.append(("center", f"유효기간 ~ {(day + timedelta(days=t['coupon_gap'])).strftime(t['coupon_fmt'])}", 18))
        traps.append("coupon_date")

    label = {"store": store, "total": paid, "date": day.isoformat()}
    meta = {"kind": kind, "date_fmt": date_fmt, "money": money, "header": header_style,
            "discount": int(discount), "traps": traps}
    return rows, label, meta


# ---------------------------------------------------------------- 그리기
def render(rows: list) -> Image.Image:
    img = Image.new("L", (W, 2400), 255)
    d = ImageDraw.Draw(img)
    y = 24
    for r in rows:
        kind = r[0]
        if kind == "center":
            ft = font(r[2], r[3] if len(r) > 3 else False)
            w = d.textlength(r[1], font=ft)
            d.text(((W - w) / 2, y), r[1], font=ft, fill=0)
            y += r[2] + 10
        elif kind == "line":
            d.text((20, y), "-" * 60, font=font(18), fill=0)
            y += 26
        elif kind == "row":  # (row, 왼쪽, 가운데, 오른쪽, 크기, 굵게)
            size = r[4] if len(r) > 4 else 21
            ft = font(size, r[5] if len(r) > 5 else False)
            if r[1]:
                d.text((24, y), r[1], font=ft, fill=0)
            if r[2]:
                w = d.textlength(r[2], font=ft)
                d.text((380 - w, y), r[2], font=ft, fill=0)
            if r[3]:
                w = d.textlength(r[3], font=ft)
                d.text((W - 24 - w, y), r[3], font=ft, fill=0)
            y += size + 12
    return img.crop((0, 0, W, y + 30))


def photo_effect(img: Image.Image, rng: random.Random, hard: bool = False) -> Image.Image:
    """휴대폰으로 찍은 느낌: 배경 + 기울기 + 조명 얼룩 + 노이즈 + 흐림 (hard는 더 세게 + 그림자 띠)"""
    pad = 80
    bg_tone = rng.randint(110, 180)
    bg = Image.new("L", (img.width + pad * 2, img.height + pad * 2), bg_tone)
    bg.paste(img, (pad, pad))
    angle = rng.uniform(-6, 6) if hard else rng.uniform(-3, 3)
    bg = bg.rotate(angle, resample=Image.BICUBIC, expand=True, fillcolor=bg_tone)
    a = np.asarray(bg).astype(np.float32)
    h, w = a.shape
    start = rng.uniform(0.9, 1.0)
    end = rng.uniform(0.6, 0.85) if hard else rng.uniform(0.7, 0.9)
    grad = np.linspace(start, end, w)[None, :] * np.linspace(0.95, 1.0, h)[:, None]
    noise_seed = rng.randint(0, 10**9)
    sigma = rng.uniform(5, 12) if hard else rng.uniform(3, 9)
    noise = np.random.default_rng(noise_seed).normal(0, sigma, a.shape)
    a = a * grad + noise
    if hard and rng.random() < 0.5:
        # 그림자 띠: 손이나 휴대폰 그림자가 가로로 드리운 느낌 (너무 어두우면 OCR 문제가 돼서 적당히)
        y0, band = rng.randint(0, h - 1), rng.randint(h // 10, h // 4)
        a[y0:y0 + band, :] *= rng.uniform(0.65, 0.85)
    out = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))
    blur = rng.uniform(0.5, 1.4) if hard else rng.uniform(0.4, 1.1)
    return out.filter(ImageFilter.GaussianBlur(blur))


# ---------------------------------------------------------------- 실행
def main():
    p = argparse.ArgumentParser()
    p.add_argument("--level", choices=["easy", "hard"], default="hard", help="easy = v1, hard = v2 (함정 추가)")
    p.add_argument("--n", type=int, default=300, help="전체 장수 (3등분)")
    p.add_argument("--out", type=Path, default=Path("mlops/data/receipts_v2"))
    p.add_argument("--seed", type=int, default=42, help="같은 seed면 항상 같은 데이터셋")
    p.add_argument("--photo-ratio", type=float, default=None, help="사진 효과 비율 (기본 easy 0.5 / hard 0.6)")
    args = p.parse_args()

    hard = args.level == "hard"
    photo_ratio = args.photo_ratio if args.photo_ratio is not None else (0.6 if hard else 0.5)
    rng = random.Random(args.seed)
    splits = ["train", "dev", "test"]
    # 1/3씩 나눔 (train은 DSPy 예시 뽑기, dev는 최적화 중 비교, test는 최종 승격 판단)
    split_of = [splits[i * 3 // args.n] for i in range(args.n)]

    args.out.mkdir(parents=True, exist_ok=True)
    for s in splits:
        (args.out / s).mkdir(exist_ok=True)

    trap_count: Counter = Counter()
    with (args.out / "labels.jsonl").open("w", encoding="utf-8") as f:
        for i in range(args.n):
            rows, label, meta = make_receipt(rng, hard)
            img = render(rows)
            photo = rng.random() < photo_ratio
            if photo:
                img = photo_effect(img, rng, hard)
            meta["photo"] = photo
            meta["level"] = args.level
            trap_count.update(meta["traps"])
            rid = f"r{i + 1:04d}"
            rel = f"{split_of[i]}/{rid}.jpg"
            img.convert("RGB").save(args.out / rel, quality=rng.choice([60, 70, 85] if hard else [75, 85, 92]))
            f.write(json.dumps({"id": rid, "split": split_of[i], "file": rel, "label": label, "meta": meta}, ensure_ascii=False) + "\n")

    counts = {s: split_of.count(s) for s in splits}
    print(f"생성 완료 ({args.level}): {args.n}장 {counts} → {args.out}")
    if trap_count:
        print("함정 분포:", ", ".join(f"{k} {v}" for k, v in trap_count.most_common()))


if __name__ == "__main__":
    main()