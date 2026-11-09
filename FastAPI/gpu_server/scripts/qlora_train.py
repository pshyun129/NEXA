"""QLoRA 학습 (GPU 서버, ~/ai/.venv-train): BF16 원본을 4bit로 올리고 언어 모델 쪽에 LoRA만 학습.

입력: mlops/data/sft/receipt_sft.jsonl (PC에서 export_sft.py로 만들어 업로드한 것)
출력: ~/ai/adapters/<이름>/ (LoRA 어댑터 = vLLM에 --lora-modules로 붙여서 서빙)
기록: MLflow 실험 receipt-qlora (손실 곡선, 설정, 데이터 해시)

시험 실행 (2스텝, 메모리·저장·vLLM 연결 확인용):
  python gpu_server/scripts/qlora_train.py --data <jsonl> --name smoke --max-steps 2
본 학습:
  python gpu_server/scripts/qlora_train.py --data <jsonl> --name receipt-qlora-YYYYMMDD

설계 메모
  - 프롬프트 형식: 데이터의 messages를 그대로 chat template에 넣음 (enable_thinking=False, 서빙과 같음)
  - 손실은 정답(assistant) 부분에만: 프롬프트(규칙 설명·영수증 원문)를 외우는 데 학습을 쓰지 않게
  - 비전 인코더·MTP 층은 학습 안 함 (이미지 입력을 안 쓰고, vLLM이 어댑터를 붙일 수 있는 층만 대상으로)
"""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path

import torch
from peft import LoraConfig, get_peft_model, prepare_model_for_kbit_training
from transformers import (AutoModelForImageTextToText, AutoTokenizer, BitsAndBytesConfig, Trainer,
                          TrainingArguments)

# 언어 모델의 일반 어텐션·MLP 층만 (선형 어텐션 층·비전·MTP 제외 → vLLM이 지원하는 이름만)
TARGET = r".*language_model.*\.(q_proj|k_proj|v_proj|o_proj|gate_proj|up_proj|down_proj)$"
END = "<|im_end|>"


def load_rows(path: Path) -> list[dict]:
    with path.open(encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def encode(tok, messages: list[dict], max_len: int) -> dict | None:
    """프롬프트(system+user) + 정답. 정답 앞부분은 라벨 -100 (손실 계산 안 함)"""
    prompt = tok.apply_chat_template(messages[:-1], tokenize=False, add_generation_prompt=True,
                                     enable_thinking=False)        # vLLM 서빙 때와 같은 생성 시작 형식
    answer = messages[-1]["content"] + END + "\n"
    p_ids = tok(prompt, add_special_tokens=False)["input_ids"]
    a_ids = tok(answer, add_special_tokens=False)["input_ids"]
    if len(p_ids) + len(a_ids) > max_len:
        return None                                                 # 잘라서 학습하면 정답이 깨짐 → 제외
    return {"input_ids": p_ids + a_ids, "labels": [-100] * len(p_ids) + a_ids}


class Collator:
    """길이가 다른 예시를 오른쪽 패딩으로 묶음"""

    def __init__(self, pad_id: int):
        self.pad_id = pad_id

    def __call__(self, batch: list[dict]) -> dict:
        n = max(len(b["input_ids"]) for b in batch)
        ids = [b["input_ids"] + [self.pad_id] * (n - len(b["input_ids"])) for b in batch]
        labels = [b["labels"] + [-100] * (n - len(b["labels"])) for b in batch]
        mask = [[1] * len(b["input_ids"]) + [0] * (n - len(b["input_ids"])) for b in batch]
        return {"input_ids": torch.tensor(ids), "labels": torch.tensor(labels), "attention_mask": torch.tensor(mask)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default=str(Path.home() / "ai/models/Qwen3.8-27B"))
    ap.add_argument("--data", required=True)
    ap.add_argument("--name", required=True, help="어댑터 이름 (저장 폴더·MLflow run 이름)")
    ap.add_argument("--out-root", default=str(Path.home() / "ai/adapters"))
    ap.add_argument("--epochs", type=float, default=2)
    ap.add_argument("--lr", type=float, default=1e-4)
    ap.add_argument("--r", type=int, default=16)
    ap.add_argument("--alpha", type=int, default=32)
    ap.add_argument("--batch", type=int, default=2)
    ap.add_argument("--accum", type=int, default=8, help="실제 배치 = batch × accum")
    ap.add_argument("--max-len", type=int, default=3072)
    ap.add_argument("--max-steps", type=int, default=-1, help="시험 실행용 (예: 2)")
    args = ap.parse_args()

    out = Path(args.out_root) / args.name
    data_path = Path(args.data)
    tok = AutoTokenizer.from_pretrained(args.model)
    if tok.pad_token_id is None:
        tok.pad_token = tok.eos_token

    rows = load_rows(data_path)
    enc = {"train": [], "eval": []}
    skipped = 0
    for r in rows:
        e = encode(tok, r["messages"], args.max_len)
        if e is None:
            skipped += 1
        else:
            enc[r["split"]].append(e)
    lens = sorted(len(e["input_ids"]) for e in enc["train"])
    print(f"학습 {len(enc['train'])} / 손실 확인 {len(enc['eval'])} / 너무 길어 제외 {skipped} | "
          f"토큰 중앙값 {lens[len(lens) // 2]} 최대 {lens[-1]}")

    # 4bit(NF4) 양자화로 올림: 27B BF16 54GB → 약 15GB
    bnb = BitsAndBytesConfig(load_in_4bit=True, bnb_4bit_quant_type="nf4", bnb_4bit_use_double_quant=True,
                             bnb_4bit_compute_dtype=torch.bfloat16)
    model = AutoModelForImageTextToText.from_pretrained(args.model, quantization_config=bnb,
                                                        dtype=torch.bfloat16, device_map={"": 0})
    model = prepare_model_for_kbit_training(model, use_gradient_checkpointing=True)

    names = [n for n, _ in model.named_modules() if re.match(TARGET, n)]
    if not names:
        raise SystemExit("LoRA 대상 층을 못 찾았습니다. 층 이름 예: " +
                         ", ".join(n for n, _ in list(model.named_modules())[:40]))
    print(f"LoRA 대상 층 {len(names)}개 (예: {names[0]})")

    model = get_peft_model(model, LoraConfig(r=args.r, lora_alpha=args.alpha, lora_dropout=0.05,
                                             target_modules=TARGET, bias="none", task_type="CAUSAL_LM"))
    model.print_trainable_parameters()

    # MLflow: run을 직접 열어두면 Trainer가 그 run에 손실을 기록하고, 끝나도 닫지 않음 → 마지막에 메타까지 같은 run에
    use_mlflow = bool(os.environ.get("MLFLOW_TRACKING_URI")) and args.max_steps < 0   # 시험 실행은 기록 안 함
    if use_mlflow:
        import mlflow
        try:
            mlflow.set_experiment("receipt-qlora")
            mlflow.start_run(run_name=args.name)
        except Exception as e:                 # 기록 실패로 밤새 학습이 멈추면 안 됨 → 기록 없이 계속
            print(f"MLflow 연결 실패, 기록 없이 학습합니다: {e}")
            use_mlflow = False
    targs = TrainingArguments(
        output_dir=str(out / "checkpoints"),
        run_name=args.name,
        num_train_epochs=args.epochs,
        max_steps=args.max_steps,
        per_device_train_batch_size=args.batch,
        per_device_eval_batch_size=args.batch,
        gradient_accumulation_steps=args.accum,
        learning_rate=args.lr,
        lr_scheduler_type="cosine",
        warmup_steps=0.05,          # transformers 5.x: 1 미만 소수 = 전체 스텝 대비 비율 (예전 warmup_ratio)
        bf16=True,
        logging_steps=5 if args.max_steps < 0 else 1,
        eval_strategy="epoch" if args.max_steps < 0 else "no",
        save_strategy="epoch" if args.max_steps < 0 else "no",
        save_total_limit=2,
        gradient_checkpointing=True,
        gradient_checkpointing_kwargs={"use_reentrant": False},
        optim="paged_adamw_8bit",
        report_to=["mlflow"] if use_mlflow else [],
        remove_unused_columns=False,
    )
    trainer = Trainer(model=model, args=targs, train_dataset=enc["train"],
                      eval_dataset=enc["eval"] or None, data_collator=Collator(tok.pad_token_id))
    trainer.train()

    model.save_pretrained(str(out))          # adapter_config.json + adapter_model.safetensors
    tok.save_pretrained(str(out))
    meta = {"name": args.name, "base": args.model, "data": str(data_path),
            "data_sha256": hashlib.sha256(data_path.read_bytes()).hexdigest()[:12],
            "r": args.r, "alpha": args.alpha, "lr": args.lr, "epochs": args.epochs, "max_steps": args.max_steps,
            "n_train": len(enc["train"]), "peak_gpu_gb": round(torch.cuda.max_memory_allocated() / 1e9, 1)}
    (out / "train_meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
    if use_mlflow:
        mlflow.log_params({f"meta_{k}": v for k, v in meta.items()})
        mlflow.end_run()
    print(f"저장: {out} | 최대 GPU 메모리 {meta['peak_gpu_gb']}GB")


if __name__ == "__main__":
    main()