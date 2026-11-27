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

    s3_endpoint_url: str = "http://127.0.0.1:9000"
    s3_public_endpoint_url: str | None = None
    s3_region: str = "us-east-1"
    s3_bucket: str = "godhead-media"
    s3_access_key: str = "godhead-dev-access"
    s3_secret_key: str = "development-only-minio-secret"
    s3_presign_ttl_seconds: int = 300

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

        storage_secret = self.s3_secret_key.strip()
        if (
            len(storage_secret) < 32
            or "development-only" in storage_secret.lower()
            or self.s3_access_key == "godhead-dev-access"
        ):
            raise ValueError(
                "Production object storage requires unique access credentials; do not use development defaults"
            )

        if not 60 <= self.s3_presign_ttl_seconds <= 900:
            raise ValueError("GODHEAD_S3_PRESIGN_TTL_SECONDS must be between 60 and 900")

        if not self.s3_bucket.strip():
            raise ValueError("GODHEAD_S3_BUCKET cannot be blank")

        return self

    @property
    def allowed_origins(self) -> list[str]:
        return [item.strip() for item in self.cors_origins.split(",") if item.strip()]


@lru_cache
def get_settings() -> Settings:
    return Settings()
