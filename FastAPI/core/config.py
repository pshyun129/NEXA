from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):

    db_host: str
    db_port: int
    db_name: str
    db_user: str
    db_password: str

    # Local LLM (vLLM on GPU server, via SSAFY VPN)
    local_llm_base_url: str = ""
    local_llm_api_key: str = ""
    local_llm_model: str = "qwen3.8-27b"
    local_llm_timeout: float = 60.0

    # OCR 서버 (GPU 서버 PyTriton, Tailscale 경유)
    ocr_base_url: str = ""          # "호스트:포트" 형식, http:// 없이 (예: 100.x.x.x:18100)
    ocr_timeout: float = 30.0       # OCR 응답 대기 최대 시간(초)

    # GMS (fallback when the local LLM is down)
    GMS_API_KEY: str = ""
    gms_base_url: str = ""
    gms_model: str = ""

    # 개발용 LLM 테스트 API 등록 여부 (운영에서는 False 유지)
    llm_test_api_enabled: bool = False

    eureka_server_url: str = ""
    instance_host: str = ""
    instance_port: int = 8086

    upload_dir: str = "uploads"
    max_upload_mb: int = 20

    # JWT 인증 (user-service가 발급한 access 토큰을 공개키로 검증, 팀원 서비스와 같은 환경변수 이름)
    jwt_jwks_uri: str = "http://localhost:8081/.well-known/jwks.json"
    auth_dev_employee_id: int | None = None   # 로컬 개발용: 토큰 없으면 이 사원으로 간주 (운영에선 비워둠)

    # 팀원 서비스 주소 (compose 안에선 http://user-service:8081 처럼 서비스 이름으로)
    user_service_url: str = "http://localhost:8081"
    approval_service_url: str = "http://localhost:8083"
    attendance_service_url: str = "http://localhost:8084"
    leave_service_url: str = "http://localhost:8082"
    service_timeout: float = 10.0

    # 결재 문서 출처: ai-service 업무 자동화 (결재 서비스 V10부터 지원)
    approval_source_type: str = "AUTOMATION"

    # Kafka (결재 결과 이벤트 구독). 비어 있으면 구독 안 함
    kafka_bootstrap_servers: str = ""
    kafka_group_id: str = "ai-service"

    # MLflow 추적 서버 (GPU 서버, Tailscale 경유). MLOps 스크립트 + 골든셋 자동 전송에서 사용
    mlflow_tracking_uri: str = ""

    golden_sync_hours: float = 24       # 골든셋 → MLflow 자동 전송 주기 (0 = 끔, 개발 PC는 .env에 0)

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")


    @property
    def database_url(self) -> str:
        return (
            f"postgresql+psycopg://{self.db_user}:{self.db_password}"
            f"@{self.db_host}:{self.db_port}/{self.db_name}"
        )

    @property
    def checkpoint_url(self) -> str:
        return(
            f"postgresql://{self.db_user}:{self.db_password}"
            f"@{self.db_host}:{self.db_port}/{self.db_name}"
        )


settings = Settings()

