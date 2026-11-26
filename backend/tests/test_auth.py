import pytest
from fastapi.testclient import TestClient
from app.config import get_settings
from app.database import Base, SessionLocal, _engine
from app.main import app

@pytest.fixture(autouse=True)
def clean_database():
    Base.metadata.drop_all(bind=_engine)
    Base.metadata.create_all(bind=_engine)
    yield
    Base.metadata.drop_all(bind=_engine)


def test_register_login_and_profile():
    with TestClient(app) as client:
        created = client.post("/v1/auth/register", json={
            "email": "User@example.com",
            "username": "Test_User",
            "password": "a-long-secure-password",
            "display_name": "Test User",
        })
        assert created.status_code == 201
        body = created.json()
        assert body["user"]["email"] == "user@example.com"
        assert "password_hash" not in body["user"]

        profile = client.get("/v1/users/me", headers={
            "Authorization": f"Bearer {body['access_token']}"
        })
        assert profile.status_code == 200
        assert profile.json()["username"] == "test_user"

        logged_in = client.post("/v1/auth/login", json={
            "email": "USER@example.com",
            "password": "a-long-secure-password",
        })
        assert logged_in.status_code == 200


def test_duplicate_registration_and_bad_login():
    with TestClient(app) as client:
        payload = {
            "email": "user@example.com",
            "username": "unique_user",
            "password": "a-long-secure-password",
            "display_name": "User",
        }
        assert client.post("/v1/auth/register", json=payload).status_code == 201
        assert client.post("/v1/auth/register", json=payload).status_code == 409
        bad = client.post("/v1/auth/login", json={
            "email": "user@example.com", "password": "wrong-password"
        })
        assert bad.status_code == 401


def test_profile_requires_token():
    with TestClient(app) as client:
        assert client.get("/v1/users/me").status_code == 401
