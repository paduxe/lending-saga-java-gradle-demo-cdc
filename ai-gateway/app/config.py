from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    anthropic_api_key: str
    claude_model: str = "claude-opus-4-8"

    redis_url: str = "redis://redis:6379"
    conversation_ttl_seconds: int = 3600
    conversation_max_turns: int = 20

    loan_service_url: str = "http://loan-service:8081"
    orchestrator_service_url: str = "http://loan-orchestrator-service:8080"
    kyc_service_url: str = "http://kyc-service:8082"
    credit_service_url: str = "http://credit-service:8083"
    disbursement_service_url: str = "http://disbursement-service:8084"

    model_config = {"env_file": ".env"}


settings = Settings()
