"""자유 프롬프트 노드: 사용자가 쓴 지시대로 앞 노드 자료를 요약·정리·문구 작성 (DSPy).

예) 지시: "아래 경비 내역을 결재 의견용으로 3줄 요약해줘"  자료: {{n3.fields}}
    지시 안에도 바인딩 가능: "{{n5.employee.name}}님의 {{n3.fields.store}} 사용 내역을 정리해줘"
분기
  완료       자료만으로 수행
  정보 부족  자료가 모자람 (missing에 무엇이 모자란지)
  확인 필요  답에 자료에 없는 숫자가 들어감 → 사람 확인
"""
from ai.modules.free_prompt import FREE_PROMPT_VERSION, run_free_prompt, to_context
from nodes.base import BaseNode, NodeContext, NodeResult


class FreePromptNode(BaseNode):
    node_type = "free_prompt"
    name = "자유 프롬프트"
    category = "ai"
    description = "지시문대로 앞 노드 자료를 요약·정리·문구 작성 (자료에 없는 내용은 만들지 않음, 판단은 하지 않음)"
    config_fields = [
        {"key": "instruction", "label": "지시 (예: 경비 내역을 3줄로 요약 · {{n3.fields.store}}처럼 값 끼워 넣기 가능)",
         "type": "text", "required": True},
        {"key": "context", "label": "자료 (예: {{n3.fields}}, 비우면 지시만 사용)", "type": "ref", "required": False},
        {"key": "output_format", "label": "결과 형식 (기본 문장)", "type": "select", "options": ["문장", "목록"],
         "required": False},
        {"key": "max_chars", "label": "최대 글자 수 (기본 500)", "type": "number", "required": False},
    ]
    branches = ["완료", "정보 부족", "확인 필요"]
    output_vars = [
        {"key": "answer", "label": "결과 글", "type": "text"},
        {"key": "status", "label": "상태 (분기 기준)", "type": "text"},
        {"key": "missing", "label": "모자란 정보", "type": "text"},
        {"key": "invented_numbers", "label": "자료에 없는 숫자", "type": "list"},
        {"key": "model_version", "label": "모델 버전", "type": "text"},
    ]

    def run(self, ctx: NodeContext) -> NodeResult:
        instruction = (ctx.config.get("instruction") or "").strip()
        if not instruction:
            raise ValueError("지시를 입력해야 합니다")
        max_chars = int(ctx.config.get("max_chars") or 500)

        r = run_free_prompt(instruction, to_context(ctx.config.get("context")),
                            ctx.config.get("output_format") or "문장")
        answer = r["answer"][:max_chars]
        # 지어낸 숫자가 있으면 '완료'여도 사람 확인
        status = "확인 필요" if r["invented_numbers"] else r["status"]
        return NodeResult(output={**r, "answer": answer, "status": status,
                                  "model_version": FREE_PROMPT_VERSION}, branch=status)