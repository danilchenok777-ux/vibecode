from functools import lru_cache

from pydantic import model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="GODHEAD_", env_file=".env", extra="ignore")

    env: str = "development"
    database_url: str = "sqlite:///./godhead.db"
    jwt_secret: str = "development-only-change-this-secret"
    access_token_minutes: int = 20
    cors_origins: str = "http://localhost:3000"

    @model_validator(mode="after")
    def validate_production_configuration(self):
        if self.env.lower() != "production":
            return self

        if self.database_url.lower().startswith("sqlite"):
            raise ValueError("Production deployments must use PostgreSQL, not SQLite")

        secret = self.jwt_secret.strip()
        placeholder = (
            secret == "development-only-change-this-secret"
            or "replace-with" in secret.lower()
            or "change-this-secret" in secret.lower()
        )
        if len(secret) < 32 or placeholder:
            raise ValueError(
                "GODHEAD_JWT_SECRET must be a unique random value of at least 32 characters in production"
            )

        if not 5 <= self.access_token_minutes <= 120:
            raise ValueError("GODHEAD_ACCESS_TOKEN_MINUTES must be between 5 and 120")

        return self

    @property
    def allowed_origins(self) -> list[str]:
        return [item.strip() for item in self.cors_origins.split(",") if item.strip()]


@lru_cache
def get_settings() -> Settings:
    return Settings()
