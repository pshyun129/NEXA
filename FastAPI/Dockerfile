FROM python:3.11-slim

WORKDIR /app

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1

COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

RUN useradd --create-home --uid 1001 appuser \
    && mkdir -p /app/uploads \
    && chown appuser:appuser /app/uploads

# 규정 RAG 모델(임베딩 약 220MB + 재정렬 약 570MB)을 빌드 때 이미지에 넣음
# → 실행 중 다운로드 없음 (EC2 외부 접속·재시작과 무관). 모델 이름이 있는 파일만 먼저 복사해서,
#   다른 코드가 바뀌어도 이 단계는 Docker 캐시를 재사용 (매번 800MB를 다시 받지 않게)
ENV FASTEMBED_CACHE_PATH=/opt/models
COPY ai/__init__.py ./ai/__init__.py
COPY ai/rag/__init__.py ai/rag/index.py ai/rag/parse.py ./ai/rag/
RUN python -m ai.rag.index \
    && chown -R appuser:appuser /opt/models
# 실행 중에는 Hugging Face에 접속하지 않음 (받아둔 파일만 사용)
ENV HF_HUB_OFFLINE=1

COPY --chown=appuser:appuser . .

USER appuser

EXPOSE 8086

CMD ["python", "-m", "uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8086"]