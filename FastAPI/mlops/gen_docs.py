"""문서 분류 평가 데이터 만들기: 5종류 × N개 OCR 풍 텍스트 → mlops/data/docs_v1/docs.jsonl (깃 제외 폴더).

실행 (FastAPI 폴더에서)
  python -X utf8 -m mlops.gen_docs              # 종류별 40개 (dev 20 / test 20)
  python -X utf8 -m mlops.gen_docs --per-class 60 --seed 7

구성
  영수증       receipts_v2의 실제 OCR 텍스트에서 무작위로 뽑음 (진짜 OCR 결과)
  세금계산서   템플릿 (제목이 없는 것도 일부 → 공급가액·세액·공급받는자로 판단해야 함)
  출장 정산서  템플릿 (여비 정산서·출장비 정산서 등 제목 변형)
  휴가 신청서  템플릿 (휴가원·연차 사용 신청서 등)
  기타         함정 위주: 메뉴판(가격은 있지만 결제 아님) · 견적서·거래명세서(공급가액·세액 있음)
               · 출장 신청서(정산 아님) · 휴가 안내 공지 · 회의록 · 택배 송장
  OCR 잡음     줄 붙음·띄어쓰기 빠짐을 무작위로 넣어 실제 OCR 결과처럼
개인정보 없음: 이름·번호는 전부 지어낸 값
"""
import argparse
import hashlib
import json
import random
from datetime import date, timedelta
from pathlib import Path

from mlops.common import load_records

OUT_DIR = Path("mlops/data/docs_v1")
NAMES = ["김민준", "이서연", "박지훈", "최수아", "정예준", "강하은", "조도윤", "윤지우", "장시우", "임서준"]
DEPTS = ["경영지원팀", "영업1팀", "개발팀", "인사팀", "마케팅팀", "재무팀"]
CORPS = ["(주)한빛오피스", "(주)대성상사", "미래테크(주)", "(주)푸른물류", "세종인쇄", "(주)온누리푸드"]
CITIES = ["부산", "대구", "광주", "대전", "울산", "제주", "강릉", "전주"]
ITEMS = [("A4용지", 25000), ("토너 카트리지", 89000), ("사무용 의자", 159000), ("모니터 암", 45000),
         ("화이트보드", 72000), ("복합기 유지보수", 120000), ("명함 인쇄", 30000)]


def _d(rng, start=date(2026, 9, 1), days=60) -> date:
    return start + timedelta(days=rng.randrange(days))


def _biz(rng) -> str:
    return f"{rng.randint(100, 899)}-{rng.randint(10, 99)}-{rng.randint(10000, 99999)}"


def _won(n: int) -> str:
    return f"{n:,}"


def _lines(rng, n=None):
    """품목 n개 (품목, 수량, 단가, 금액)"""
    picks = rng.sample(ITEMS, n or rng.randint(1, 3))
    return [(name, q, price, q * price) for (name, price), q in ((p, rng.randint(1, 5)) for p in picks)]


# ---------------------------------------------------------------- 종류별 템플릿
def tax_invoice(rng) -> str:
    sup, buy = rng.sample(CORPS, 2)
    d = _d(rng)
    rows = _lines(rng)
    supply = sum(r[3] for r in rows)
    tax = supply // 10
    title = rng.choice(["전자세금계산서", "세금계산서 (공급받는자 보관용)", "전자세금계산서(공급자 보관용)", ""])
    body = [title,
            f"승인번호 {d:%Y%m%d}{rng.randint(10, 99)}-{rng.randint(10000000, 99999999)}",
            f"공급자 등록번호 {_biz(rng)} 상호 {sup} 성명 {rng.choice(NAMES)}",
            f"공급받는자 등록번호 {_biz(rng)} 상호 {buy} 성명 {rng.choice(NAMES)}",
            f"작성일자 {d:%Y-%m-%d}  공급가액 {_won(supply)}  세액 {_won(tax)}",
            "월 일 품목 규격 수량 단가 공급가액 세액"]
    body += [f"{d.month} {d.day} {n} EA {q} {_won(p)} {_won(a)} {_won(a // 10)}" for n, q, p, a in rows]
    body += [f"합계금액 {_won(supply + tax)}", rng.choice(["이 금액을 (청구) 함", "이 금액을 (영수) 함"])]
    return "\n".join(x for x in body if x)


def trip_settlement(rng) -> str:
    start = _d(rng)
    nights = rng.randint(1, 3)
    end = start + timedelta(days=nights)
    city = rng.choice(CITIES)
    fare, hotel, daily = rng.choice([59800, 103400, 128000]), 85000 * nights, 25000 * (nights + 1)
    meal = rng.randint(2, 6) * 9000
    total = fare + hotel + daily + meal
    advance = rng.choice([0, 200000, 300000])
    title = rng.choice(["출장 여비 정산서", "출장비 정산서", "국내출장 정산서", "여비 정산 신청서"])
    return "\n".join([
        title,
        f"소속 {rng.choice(DEPTS)}  성명 {rng.choice(NAMES)}  직급 {rng.choice(['사원', '대리', '과장'])}",
        f"출장기간 {start:%Y-%m-%d} ~ {end:%Y-%m-%d} ({nights}박 {nights + 1}일)",
        f"출장지 {city}  출장목적 {rng.choice(['고객사 미팅', '현장 점검', '박람회 참관', '교육 참석'])}",
        "구분 내역 금액",
        f"교통비 KTX 왕복 {_won(fare)}",
        f"숙박비 {nights}박 {_won(hotel)}",
        f"일비 {nights + 1}일 {_won(daily)}",
        f"식비 {_won(meal)}",
        f"합계 {_won(total)}",
        f"가지급금 {_won(advance)}  차감 지급액 {_won(total - advance)}",
        "첨부 영수증 별첨",
        "담당 팀장 본부장",
    ])


def leave_request(rng) -> str:
    start = _d(rng, date(2026, 11, 1))
    kind = rng.choice(["연차", "오전 반차", "오후 반차", "경조사 휴가", "병가", "대체 휴무"])
    days = 0.5 if "반차" in kind else rng.randint(1, 3)
    end = start if days < 1 else start + timedelta(days=int(days) - 1)
    title = rng.choice(["휴가 신청서", "휴가원", "연차 사용 신청서", "휴가 신청"])
    who = rng.choice(NAMES)
    return "\n".join([
        title,
        f"소속 {rng.choice(DEPTS)}  성명 {who}",
        f"휴가 종류 {kind}",
        f"기간 {start:%Y-%m-%d} ~ {end:%Y-%m-%d} ({days}일)",
        f"사유 {rng.choice(['개인 사정', '가족 행사', '병원 진료', '이사', '휴식'])}",
        f"업무 인수자 {rng.choice(NAMES)}",
        "비상연락처 010-0000-0000",
        "위와 같이 휴가를 신청합니다.",
        f"{date(2026, 10, rng.randint(1, 31)):%Y년 %m월 %d일}  신청인 {who} (인)",
    ])


# ---------------------------------------------------------------- 기타 (함정)
def menu(rng) -> str:
    store = rng.choice(["카페 온도", "한솥밥상", "브루클린 버거", "소담 칼국수"])
    picks = rng.sample(["아메리카노 4,500", "카페라떼 5,000", "치즈버거 8,900", "칼국수 9,000",
                        "제육덮밥 9,500", "콜드브루 5,500", "감자튀김 3,500", "만두 6,000"], 5)
    return "\n".join([store, "MENU", *picks, "포장 가능 / 단체 예약 문의", "TEL 02-000-0000"])


def quote(rng) -> str:
    rows = _lines(rng)
    supply = sum(r[3] for r in rows)
    return "\n".join([
        rng.choice(["견 적 서", "견적서", "QUOTATION 견적서"]),
        f"수신 {rng.choice(CORPS)} 귀중",
        f"견적일 {_d(rng):%Y-%m-%d}  유효기간 견적일로부터 30일",
        "아래와 같이 견적합니다.",
        *[f"{n} {q} {_won(p)} {_won(a)}" for n, q, p, a in rows],
        f"공급가액 {_won(supply)}  세액 {_won(supply // 10)}  합계 {_won(supply * 11 // 10)}",
        f"공급자 {rng.choice(CORPS)}  사업자번호 {_biz(rng)}",
    ])


def statement(rng) -> str:
    rows = _lines(rng)
    supply = sum(r[3] for r in rows)
    return "\n".join([
        "거래명세서 (공급받는자용)",
        f"거래일자 {_d(rng):%Y-%m-%d}",
        f"공급자 {rng.choice(CORPS)}  등록번호 {_biz(rng)}",
        *[f"{n} {q} {_won(p)} {_won(a)}" for n, q, p, a in rows],
        f"공급가액 {_won(supply)}  세액 {_won(supply // 10)}",
        "인수자 (인)",
    ])


def trip_plan(rng) -> str:
    start = _d(rng, date(2026, 11, 10))
    return "\n".join([
        rng.choice(["출장 신청서", "출장 계획서", "출장 품의서"]),
        f"소속 {rng.choice(DEPTS)}  성명 {rng.choice(NAMES)}",
        f"출장 예정 기간 {start:%Y-%m-%d} ~ {start + timedelta(days=2):%Y-%m-%d}",
        f"출장지 {rng.choice(CITIES)}  목적 {rng.choice(['신규 거래처 발굴', '설치 지원'])}",
        f"예상 경비 약 {_won(rng.choice([300000, 450000, 600000]))}원",
        "위와 같이 출장을 신청하오니 승인하여 주시기 바랍니다.",
    ])


def leave_notice(rng) -> str:
    return "\n".join([
        "[공지] 연말 휴가 사용 안내",
        "임직원 여러분께,",
        "올해 남은 연차는 12월 31일까지 사용해 주시기 바랍니다.",
        "휴가 신청은 그룹웨어에서 팀장 결재 후 확정됩니다.",
        f"문의 {rng.choice(DEPTS)} 내선 {rng.randint(1000, 9999)}",
    ])


def minutes(rng) -> str:
    return "\n".join([
        "회의록",
        f"일시 {_d(rng):%Y-%m-%d} 14:00  장소 3층 회의실",
        f"참석자 {', '.join(rng.sample(NAMES, 4))}",
        "안건 1. 4분기 매출 점검 2. 법인카드 사용 기준 공유",
        "결정 사항 회의비는 1인당 1만 원 이내로 집행",
        "다음 회의 2주 후",
    ])


def parcel(rng) -> str:
    return "\n".join([
        rng.choice(["택배 운송장", "운송장 (고객용)"]),
        f"운송장번호 {rng.randint(1000, 9999)}-{rng.randint(1000, 9999)}-{rng.randint(1000, 9999)}",
        f"받는 분 {rng.choice(NAMES)}  010-0000-0000",
        "주소 서울특별시 강남구 테헤란로 000",
        f"보내는 분 {rng.choice(CORPS)}",
        f"품목 사무용품  수량 {rng.randint(1, 4)}  운임 선불",
    ])


OTHERS = [menu, quote, statement, trip_plan, leave_notice, minutes, parcel]
MAKERS = {"세금계산서": tax_invoice, "출장 정산서": trip_settlement, "휴가 신청서": leave_request}


def ocr_noise(rng, text: str) -> str:
    """OCR 결과처럼: 두 줄이 붙거나(15%), 줄 안 띄어쓰기가 사라짐(10%)"""
    out = []
    for line in text.split("\n"):
        if out and rng.random() < 0.15:
            out[-1] += " " + line
        elif rng.random() < 0.10:
            out.append(line.replace(" ", ""))
        else:
            out.append(line)
    return "\n".join(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--per-class", type=int, default=40)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--out", default=str(OUT_DIR))
    args = ap.parse_args()
    rng = random.Random(args.seed)

    docs = []
    receipts = rng.sample(load_records(), args.per_class)   # 진짜 OCR 결과 (receipts_v2)
    docs += [{"doc_type": "영수증", "source": f"receipts_v2/{r['id']}", "text": r["text"]} for r in receipts]
    for doc_type, make in MAKERS.items():
        docs += [{"doc_type": doc_type, "source": make.__name__, "text": ocr_noise(rng, make(rng))}
                 for _ in range(args.per_class)]
    for i in range(args.per_class):                          # 기타: 함정 종류를 골고루
        make = OTHERS[i % len(OTHERS)]
        docs.append({"doc_type": "기타", "source": make.__name__, "text": ocr_noise(rng, make(rng))})

    rng.shuffle(docs)
    for i, d in enumerate(docs):
        d["id"] = f"d{i + 1:04d}"
    # 종류마다 절반씩 dev / test
    seen: dict[str, int] = {}
    for d in docs:
        seen[d["doc_type"]] = seen.get(d["doc_type"], 0) + 1
        d["split"] = "dev" if seen[d["doc_type"]] % 2 else "test"

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    path = out / "docs.jsonl"
    with path.open("w", encoding="utf-8") as f:
        for d in docs:
            f.write(json.dumps({k: d[k] for k in ("id", "split", "doc_type", "source", "text")}, ensure_ascii=False) + "\n")
    sha = hashlib.sha256(path.read_bytes()).hexdigest()[:12]
    counts = {t: sum(d["doc_type"] == t for d in docs) for t in ["영수증", *MAKERS, "기타"]}
    print(f"{path} | {len(docs)}개 {counts} | sha {sha}")


if __name__ == "__main__":
    main()