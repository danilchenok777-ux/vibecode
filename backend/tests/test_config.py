import pytest
from pydantic import ValidationError

from app.config import Settings


def test_development_settings_keep_local_sqlite_default():
    settings = Settings(_env_file=None)
    assert settings.env == "development"
    assert settings.database_url.startswith("sqlite")


@pytest.mark.parametrize(
    "secret",
    [
        "development-only-change-this-secret",
        "replace-with-a-long-random-secret-before-deploy",
        "too-short",
    ],
)
def test_production_rejects_placeholder_or_short_jwt_secret(secret):
    with pytest.raises(ValidationError, match="GODHEAD_JWT_SECRET"):
        Settings(
            _env_file=None,
            env="production",
            database_url="postgresql+psycopg://godhead:secret@localhost/godhead",
            jwt_secret=secret,
        )


def test_production_rejects_sqlite():
    with pytest.raises(ValidationError, match="PostgreSQL"):
        Settings(
            _env_file=None,
            env="production",
            database_url="sqlite:///./godhead.db",
            jwt_secret="a-unique-production-secret-with-at-least-32-characters",
        )


def test_production_rejects_unreasonable_access_token_lifetime():
    with pytest.raises(ValidationError, match="ACCESS_TOKEN_MINUTES"):
        Settings(
            _env_file=None,
            env="production",
            database_url="postgresql+psycopg://godhead:secret@localhost/godhead",
            jwt_secret="a-unique-production-secret-with-at-least-32-characters",
            access_token_minutes=1440,
        )


def test_valid_production_settings_are_accepted():
    settings = Settings(
        _env_file=None,
        env="production",
        database_url="postgresql+psycopg://godhead:secret@localhost/godhead",
        jwt_secret="a-unique-production-secret-with-at-least-32-characters",
        access_token_minutes=20,
        s3_access_key="godhead-production-storage",
        s3_secret_key="a-unique-production-storage-secret-with-at-least-32-characters",
    )
    assert settings.env == "production"
