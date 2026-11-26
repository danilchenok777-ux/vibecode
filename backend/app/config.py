from functools import lru_cache
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="GODHEAD_", env_file=".env", extra="ignore")

    env: str = "development"
    database_url: str = "sqlite:///./godhead.db"
    jwt_secret: str = "development-only-change-this-secret"
    access_token_minutes: int = 20
    cors_origins: str = "http://localhost:3000"

    @property
    def allowed_origins(self) -> list[str]:
        return [item.strip() for item in self.cors_origins.split(",") if item.strip()]


@lru_cache
def get_settings() -> Settings:
    return Settings()
