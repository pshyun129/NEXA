"""자유 프롬프트 DSPy 모듈: 사용자가 쓴 지시 + 앞 노드 자료 → 답 (요약·정리·문구 작성 등).

할루시네이션 방어
  - 자료에 없는 사실은 쓰지 않고, 모자라면 status='정보 부족' + 무엇이 모자란지(missing)
  - 코드 검사: 답에 나온 숫자(10 이상)가 지시·자료에 없으면 '지어낸 숫자' → 노드가 '확인 필요'로 보냄
  - 판단·승인은 하지 않음 (판정은 규정 검토 노드, 결정은 사람·결재)
"""
import json
import re
from typing import Literal

import dspy
from pydantic import BaseModel, Field

from ai.dspy_lm import run_with_fallback

FREE_PROMPT_VERSION = "free_prompt@base"
MAX_CONTEXT = 6000                 # 자료가 너무 길면 앞부분만 (토큰 한도·지연 방지)
NUM = re.compile(r"\d[\d,]*(?:\.\d+)?")


class FreeAnswer(BaseModel):
    status: Literal["완료", "정보 부족"] = Field(description="자료만으로 지시를 수행할 수 있으면 '완료', 아니면 '정보 부족'")
    answer: str = Field(description="지시에 대한 결과. 정보 부족이면 할 수 있는 부분까지만")
    missing: str = Field(description="정보 부족일 때 모자란 정보 (완료면 빈 문자열)")


class FreeTask(dspy.Signature):
    """사내 업무 자동화의 한 단계로, 주어진 자료만 사용해 지시를 수행한다.
    1. 자료에 없는 사실·숫자·이름은 만들지 않는다. 계산이 필요하면 자료의 숫자만 쓴다.
    2. 자료가 모자라면 status를 '정보 부족'으로 하고 missing에 무엇이 모자란지 적는다.
    3. 승인·반려·규정 위반 여부 같은 판단은 하지 않는다 (요청받아도 '판단은 담당자 확인 필요'라고 쓴다).
    4. 한국어로, 지시한 형식과 길이를 지킨다."""

    instruction: str = dspy.InputField(desc="사용자가 노드에 적은 지시")
    context: str = dspy.InputField(desc="앞 노드에서 받은 자료 (없으면 빈 문자열)")
    output_format: str = dspy.InputField(desc="결과 형식: 문장 / 목록")
    result: FreeAnswer = dspy.OutputField()


class FreePrompt(dspy.Module):
    def __init__(self):
        super().__init__()
        self.run = dspy.Predict(FreeTask)

    def forward(self, instruction: str, context: str, output_format: str):
        return self.run(instruction=instruction, context=context, output_format=output_format)


_program = FreePrompt()


def to_context(value) -> str:
    """바인딩 값(dict·list·문자열)을 LLM에 넣을 글자로. dict는 JSON 그대로 (키 이름이 곧 설명)"""
    if value is None:
        return ""
    text = value if isinstance(value, str) else json.dumps(value, ensure_ascii=False, default=str)
    return text[:MAX_CONTEXT]


def _nums(text: str) -> set[str]:
    """10 이상 숫자만 (목록 번호 1. 2. 같은 것 제외), 쉼표 제거"""
    out = set()
    for m in NUM.findall(text or ""):
        n = m.replace(",", "").rstrip(".")
        try:
            if float(n) >= 10:
                out.add(n)
        except ValueError:
            pass
    return out


def ungrounded_numbers(answer: str, *sources: str) -> list[str]:
    """답에 있는데 지시·자료 어디에도 없는 숫자"""
    known = set().union(*(_nums(s) for s in sources))
    return sorted(_nums(answer) - known)


def run_free_prompt(instruction: str, context: str = "", output_format: str = "문장", runner=None) -> dict:
    run = runner or run_with_fallback
    r = run(_program, instruction=instruction, context=context, output_format=output_format).result
    invented = ungrounded_numbers(r.answer, instruction, context)
    return {"status": r.status, "answer": r.answer.strip(), "missing": r.missing.strip(), "invented_numbers": invented}