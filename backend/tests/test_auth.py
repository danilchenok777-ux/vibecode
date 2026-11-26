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


def test_user_search_requires_auth_and_returns_only_public_fields():
    with TestClient(app) as client:
        alice = client.post("/v1/auth/register", json={
            "email": "alice-private@example.com",
            "username": "alice_smith",
            "password": "a-long-secure-password",
            "display_name": "Alice Smith",
        })
        bob = client.post("/v1/auth/register", json={
            "email": "bob-private@example.com",
            "username": "bobby",
            "password": "a-long-secure-password",
            "display_name": "Bob Example",
        })
        assert alice.status_code == 201
        assert bob.status_code == 201
        bob_token = bob.json()["access_token"]

        anonymous = client.get("/v1/users/search?query=alice")
        assert anonymous.status_code == 401

        response = client.get(
            "/v1/users/search?query=%40alice",
            headers={"Authorization": f"Bearer {bob_token}"},
        )
        assert response.status_code == 200
        results = response.json()
        assert len(results) == 1
        assert results[0]["username"] == "alice_smith"
        assert results[0]["display_name"] == "Alice Smith"
        assert "email" not in results[0]
        assert "password_hash" not in results[0]

        too_short = client.get(
            "/v1/users/search?query=%40a",
            headers={"Authorization": f"Bearer {bob_token}"},
        )
        assert too_short.status_code == 422
