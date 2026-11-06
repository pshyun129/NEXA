"""5-5 DSPy 최적화: train으로 지시문·예시 후보를 만들고 dev 점수로 골라 도전자(challenger) 프로그램을 만든다.

  python -X utf8 -m mlops.optimize                   # MIPROv2 light (기본)
  python -X utf8 -m mlops.optimize --auto medium     # 후보를 더 많이 탐색 (더 오래 걸림)

결과: mlops/data/programs/miprov2-날짜-시각.json + MLflow run (stage=optimize)
test는 여기서 절대 쓰지 않는다 → 5-6 승격 판정용으로 아껴둠 (dev에 맞춰진 점수로 승격하면 안 되니까)
"""
import argparse
import random
import logging
import time
from pathlib import Path

import dspy
from dspy.teleprompt import MIPROv2

from core.config import settings
from mlops.common import DATA_DIR, dspy_metric, load_program, load_records, require_local_lm, squash, to_examples
from mlops.evaluate import breakdown, log_to_mlflow, print_report, run_eval, summarize

logging.getLogger("LiteLLM").setLevel(logging.WARNING)
OUT_DIR = Path("mlops/data/programs")       # mlops/data 아래라 git에 안 올라감 (원본은 MLflow artifact)


def local_lm(temperature: float, max_tokens: int = 1024) -> dspy.LM:
    """최적화 전용 로컬 LM.
    운영용(get_local_lm)과 다른 점: 재시도 2회 → 수백 번 호출하다 한 번 끊겨도 전체가 죽지 않게"""
    return dspy.LM(
        model=f"openai/{settings.local_llm_model}",
        api_base=settings.local_llm_base_url,
        api_key=settings.local_llm_api_key,
        temperature=temperature,
        max_tokens=max_tokens,
        timeout=settings.local_llm_timeout,
        num_retries=2,
        cache=False,
        extra_body={"chat_template_kwargs": {"enable_thinking": False}},
    )


HARD_EXTRA = Path("mlops/data/receipts_v2_train")   # QLoRA용으로 만든 추가 학습 영수증 (seed 7, dev·test와 겹치지 않음)


def _one_edit(a: str, b: str) -> bool:
    """두 문자열이 글자 1개 차이 이내인지 (바꿈·빠짐·더함)"""
    if abs(len(a) - len(b)) > 1:
        return False
    if len(a) == len(b):
        return sum(x != y for x, y in zip(a, b)) <= 1
    short, long_ = sorted((a, b), key=len)
    return any(long_[:i] + long_[i + 1:] == short for i in range(len(long_)))


def store_in_text(label: str, text: str) -> str:
    """정답 상호가 OCR 텍스트에 어떻게 보이는지
    exact  그대로 보임
    near   한 글자만 다르게 보임 (OCR 오타, 예: 한솥→한솔) → 고칠 근거가 텍스트에 있음
    absent 아예 안 보임 → 맞히려면 지어내야 함 (QLoRA가 배운 '지어내기'의 원인이라 학습 예시에서 제외)"""
    lab, txt = squash(label), squash(text)
    if lab in txt:
        return "exact"
    for n in (len(lab) - 1, len(lab), len(lab) + 1):
        if any(_one_edit(lab, txt[i:i + n]) for i in range(max(0, len(txt) - n) + 1)):
            return "near"
    return "absent"


def mine_hard(program, data: Path, threads: int, seed: int) -> tuple[list[dict], dict]:
    """틀린 예제 위주 학습셋: 기본 프로그램이 틀리는 'OCR 한 글자 오타' 영수증 + 같은 수의 쉬운 영수증
    (쉬운 것도 섞는 이유: 오타만 보면 멀쩡한 상호까지 고치려 들 수 있음)
    주의: receipts_v2와 receipts_v2_train은 id가 둘 다 r0001부터라 겹침 → id 대신 순서로 짝을 맞춤"""
    pool = load_records("train", data)
    if HARD_EXTRA.exists():
        pool += load_records(None, HARD_EXTRA)
    kinds = [store_in_text(r["label"]["store"], r["text"]) for r in pool]
    candidates = [r for r, k in zip(pool, kinds) if k == "near"]
    print(f"틀린 예제 후보 (상호 한 글자 오타) {len(candidates)}장 → 기본 프로그램으로 확인 중")
    rows = run_eval(program, candidates, threads)
    hard = [r for r, row in zip(candidates, rows) if not row["all_ok"]]
    easy_pool = [r for r, k in zip(pool, kinds) if k == "exact"]
    easy = random.Random(seed).sample(easy_pool, min(len(hard), len(easy_pool)))
    stats = {"pool_n": len(pool), "hard_candidates": len(candidates), "hard_n": len(hard), "easy_n": len(easy),
             "absent_excluded": kinds.count("absent")}
    return hard + easy, stats


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--data", type=Path, default=DATA_DIR)
    p.add_argument("--auto", choices=["light", "medium", "heavy"], default="light", help="탐색량 (light ≈ 10~20분)")
    p.add_argument("--demos", type=int, default=3, help="프롬프트에 넣을 예시 최대 개수 (수집 예시·정답 예시 각각)")
    p.add_argument("--threads", type=int, default=4)
    p.add_argument("--seed", type=int, default=42)
    p.add_argument("--hard", action="store_true",
                   help="틀린 예제 위주: 기본 프로그램이 틀리는 OCR 한 글자 오타 영수증 + 같은 수의 쉬운 영수증으로 학습")
    args = p.parse_args()

    require_local_lm()                                     # 로컬 LLM 설정 확인
    hard_stats = {}
    if args.hard:
        train_records, hard_stats = mine_hard(load_program(), args.data, args.threads, args.seed)
        print(f"틀린 예제 {hard_stats['hard_n']}장 + 쉬운 예제 {hard_stats['easy_n']}장 "
              f"(후보 {hard_stats['hard_candidates']}장, 상호가 아예 안 보이는 {hard_stats['absent_excluded']}장은 제외)")
    else:
        train_records = load_records("train", args.data)
    train = to_examples(train_records)
    dev_records = load_records("dev", args.data)
    dev = to_examples(dev_records)

    task_lm = local_lm(0.0)                                # 실제 추출: 항상 같은 답
    prompt_lm = local_lm(0.7, max_tokens=4096)             # 지시문 후보 작성: 다양하게 쓰도록 온도 올림
    dspy.configure(lm=task_lm)

    optimizer = MIPROv2(
        metric=dspy_metric,
        prompt_model=prompt_lm,
        task_model=task_lm,
        auto=args.auto,
        max_bootstrapped_demos=args.demos,                 # 모델이 직접 맞힌 답 중에서 고른 예시
        max_labeled_demos=args.demos,                      # 정답 라벨을 그대로 쓴 예시 (오타 교정을 보여줄 수 있음)
        num_threads=args.threads,
        seed=args.seed,
    )
    print(f"MIPROv2({args.auto}) 시작: train {len(train)}장으로 후보 생성, dev {len(dev)}장으로 채점")
    start = time.time()
    optimized = optimizer.compile(load_program(), trainset=train, valset=dev)
    minutes = (time.time() - start) / 60

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    name = f"miprov2{'-hard' if args.hard else ''}-{time.strftime('%Y%m%d-%H%M')}"
    path = OUT_DIR / f"{name}.json"
    optimized.save(str(path))

    print(f"\n최적화 완료 ({minutes:.1f}분) → {path}")
    print("[선택된 지시문]")
    print(optimized.extract.signature.instructions)
    print(f"[예시 개수] {len(optimized.extract.demos)}")

    # 도전자를 dev로 다시 채점 → base와 같은 형식으로 비교 (평가는 운영과 같은 조건: 재시도 0, 온도 0)
    rows = run_eval(optimized, dev_records, args.threads)
    s, bd = summarize(rows), breakdown(rows)
    print_report(name, "dev", args.data.name, s, bd, rows)

    tags = {
        "stage": "optimize", "optimizer": "MIPROv2", "auto": args.auto,
        "max_demos": str(args.demos), "seed": str(args.seed),
        "train_n": str(len(train)), "opt_minutes": f"{minutes:.1f}",
        "hard": str(args.hard), **{k: str(v) for k, v in hard_stats.items()},
    }
    run_id = log_to_mlflow(name, "dev", args.data, optimized, str(path), s, bd, rows, args.threads, tags=tags)
    print(f"\nMLflow 기록 완료: run_id={run_id}")


if __name__ == "__main__":
    main()