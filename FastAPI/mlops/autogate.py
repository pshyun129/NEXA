"""자동 승격 게이트 (밤 작업용). 사람이 결과를 안 보고 승격하므로 수동 게이트(promote.py)보다 엄격하게.

안전장치
  ① 같은 test를 두 번 돌려 두 번 다 통과해야 함 — vLLM은 온도 0이어도 결과가 조금씩 흔들려서, 한 번 통과는 운일 수 있음
  ② 골든셋 평가용(실제 영수증)에서도 나빠지면 안 됨 — 합성 데이터만 외워서 좋아진 후보를 막음
     QLoRA 어댑터는 골든셋 평가용이 MIN_ROWS장 이상일 때만 승격 가능 (합성 데이터로만 좋아 보인 v1 어댑터가 그 사례)
  ③ 규정 판정: 놓친 위반·잘못된 준수는 0이어야 하고, 정답이 1건 이상 늘어야 함
"""
from ai.dspy_lm import get_local_lm
from mlops import golden_split
from mlops.common import DATA_DIR, FIELDS, load_records
from mlops.evaluate import run_eval, summarize
from mlops.promote import EPS, MAX_LATENCY_RATIO, gate as receipt_rules

ROUNDS = 2


def _checks(checks) -> list[dict]:
    return [{"rule": r, "ok": bool(ok), "detail": d} for r, ok, d in checks]


def golden_rules(champ: dict, chal: dict) -> tuple[bool, list]:
    """실제 영수증: 더 좋아질 필요는 없고(장수가 적어 차이가 잘 안 보임) 나빠지지만 않으면 됨"""
    checks = [("전부 정답 하락 없음", chal["acc_all"] >= champ["acc_all"] - EPS, f"{champ['acc_all']:.1%} → {chal['acc_all']:.1%}")]
    for k in FIELDS:
        checks.append((f"{k} 하락 없음", chal[f"acc_{k}"] >= champ[f"acc_{k}"] - EPS,
                       f"{champ[f'acc_{k}']:.1%} → {chal[f'acc_{k}']:.1%}"))
    checks.append(("지어낸 오답 증가 없음", chal["invented"] <= champ["invented"], f"{champ['invented']}개 → {chal['invented']}개"))
    return all(ok for _, ok, _ in checks), checks


def receipt_gate(champ_prog, chal_prog, champ_model: str | None = None, chal_model: str | None = None,
                 require_golden: bool = False, threads: int = 4) -> dict:
    """영수증 추출 후보(프로그램 또는 어댑터 모델) 판정 → {passed, reason, rounds, golden, chal_rows}"""
    records = load_records("test", DATA_DIR)
    champ_lm, chal_lm = get_local_lm(champ_model), get_local_lm(chal_model)
    rounds, chal_rows = [], []
    for i in range(ROUNDS):
        c = summarize(run_eval(champ_prog, records, threads, lm=champ_lm))
        chal_rows = run_eval(chal_prog, records, threads, lm=chal_lm)
        h = summarize(chal_rows)
        ok, checks = receipt_rules(c, h)
        rounds.append({"round": i + 1, "passed": ok, "checks": _checks(checks), "champion": c, "challenger": h})
        print(f"  [{i + 1}/{ROUNDS}] 합성 test {len(records)}장: {c['acc_all']:.1%} → {h['acc_all']:.1%} "
              f"(지어낸 오답 {c['invented']} → {h['invented']}) {'통과' if ok else '탈락'}")

    golden = golden_split.load("test")
    g = None
    if len(golden) >= golden_split.MIN_ROWS:
        c = summarize(run_eval(champ_prog, golden, threads, lm=champ_lm))
        h = summarize(run_eval(chal_prog, golden, threads, lm=chal_lm))
        ok, checks = golden_rules(c, h)
        g = {"passed": ok, "n": len(golden), "checks": _checks(checks), "champion": c, "challenger": h}
        print(f"  골든 평가용 {len(golden)}장: {c['acc_all']:.1%} → {h['acc_all']:.1%} {'통과' if ok else '탈락'}")

    synth_ok = all(r["passed"] for r in rounds)
    if not synth_ok:
        failed = [c["rule"] for r in rounds for c in r["checks"] if not c["ok"]]
        reason = "합성 test 규칙 실패: " + ", ".join(dict.fromkeys(failed))
    elif g is None and require_golden:
        reason = f"판정 보류: 골든 평가용이 {len(golden)}장 (≥{golden_split.MIN_ROWS}장 필요)"
    elif g is not None and not g["passed"]:
        reason = "골든 평가용에서 나빠짐: " + ", ".join(c["rule"] for c in g["checks"] if not c["ok"])
    else:
        reason = "모든 규칙 통과" + ("" if g else " (골든 평가용 부족 → 합성 test만)")
    passed = synth_ok and (g["passed"] if g else not require_golden)
    hold = synth_ok and g is None and require_golden
    return {"passed": passed, "hold": hold, "reason": reason, "rounds": rounds, "golden": g, "chal_rows": chal_rows}


# ---------------------------------------------------------------- 규정 판정
def policy_rules(champ: dict, chal: dict, n: int) -> tuple[bool, list]:
    gain = chal["verdict_acc"] - champ["verdict_acc"]
    checks = [
        ("정답 1건 이상 증가", gain >= 1 / n - EPS, f"{champ['verdict_acc']:.1%} → {chal['verdict_acc']:.1%}"),
        ("놓친 위반 0", chal["missed_violations"] == 0, str(chal["missed_violations"])),
        ("잘못된 준수 0", chal["unsafe_pass"] == 0, str(chal["unsafe_pass"])),
        ("거짓 위반 증가 없음", chal["false_violations"] <= champ["false_violations"],
         f"{champ['false_violations']} → {chal['false_violations']}"),
        ("오류 증가 없음", chal["errors"] <= champ["errors"], f"{champ['errors']} → {chal['errors']}"),
        (f"지연 {MAX_LATENCY_RATIO}배 이내", chal["latency_ms"] <= champ["latency_ms"] * MAX_LATENCY_RATIO,
         f"{champ['latency_ms']:.0f}ms → {chal['latency_ms']:.0f}ms"),
    ]
    return all(ok for _, ok, _ in checks), checks


def policy_gate(champ_judge, chal_judge, data: dict, threads: int = 2) -> dict:
    """판정 프로그램 후보 판정 (policy_v1 test 케이스, 두 번)"""
    from concurrent.futures import ThreadPoolExecutor

    from mlops.policy_eval import eval_case, summarize as policy_summary

    cases = [c for c in data["cases"] if c["split"] == "test"]

    def run(judge):
        with ThreadPoolExecutor(max_workers=threads) as pool:
            return policy_summary(list(pool.map(lambda c: eval_case(c, data["today"], judge=judge), cases)))

    rounds = []
    for i in range(ROUNDS):
        c, h = run(champ_judge), run(chal_judge)
        ok, checks = policy_rules(c, h, len(cases))
        rounds.append({"round": i + 1, "passed": ok, "checks": _checks(checks), "champion": c, "challenger": h})
        print(f"  [{i + 1}/{ROUNDS}] 규정 test {len(cases)}건: {c['verdict_acc']:.1%} → {h['verdict_acc']:.1%} "
              f"(놓친 위반 {h['missed_violations']}, 잘못된 준수 {h['unsafe_pass']}) {'통과' if ok else '탈락'}")
    passed = all(r["passed"] for r in rounds)
    failed = [c["rule"] for r in rounds for c in r["checks"] if not c["ok"]]
    return {"passed": passed, "hold": False, "rounds": rounds,
            "reason": "모든 규칙 통과" if passed else "규칙 실패: " + ", ".join(dict.fromkeys(failed))}
