import pytest
from datetime import datetime, timedelta, timezone
from fastapi.testclient import TestClient
from app.config import get_settings
from app.database import Base, SessionLocal, _engine
from app.main import app
from app.security import reset_auth_rate_limits_for_tests
from app.security import decode_access_token_claims
from app.models import AuthSession

@pytest.fixture(autouse=True)
def clean_database():
    reset_auth_rate_limits_for_tests()
    Base.metadata.drop_all(bind=_engine)
    Base.metadata.create_all(bind=_engine)
    yield
    Base.metadata.drop_all(bind=_engine)
    reset_auth_rate_limits_for_tests()


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


def test_login_attempts_are_rate_limited():
    with TestClient(app) as client:
        registered = client.post("/v1/auth/register", json={
            "email": "rate_limit@example.com",
            "username": "rate_limit_user",
            "password": "a-long-secure-password",
            "display_name": "Rate Limit User",
        })
        assert registered.status_code == 201

        for _ in range(10):
            response = client.post("/v1/auth/login", json={
                "email": "rate_limit@example.com",
                "password": "wrong-password",
            })
            assert response.status_code == 401
        limited = client.post("/v1/auth/login", json={
            "email": "rate_limit@example.com",
            "password": "wrong-password",
        })
        assert limited.status_code == 429
        assert int(limited.headers["retry-after"]) > 0


def test_cors_preflight_includes_message_and_session_verbs():
    with TestClient(app) as client:
        response = client.options("/v1/conversations/1/read", headers={
            "Origin": "http://localhost:3000",
            "Access-Control-Request-Method": "PUT",
            "Access-Control-Request-Headers": "authorization,content-type",
        })
        assert response.status_code == 200
        assert "PUT" in response.headers["access-control-allow-methods"]
        assert "authorization" in response.headers["access-control-allow-headers"].lower()


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


def test_logout_revokes_only_the_current_access_session():
    with TestClient(app) as client:
        registered = client.post("/v1/auth/register", json={
            "email": "session@example.com",
            "username": "session_user",
            "password": "a-long-secure-password",
            "display_name": "Session User",
        })
        assert registered.status_code == 201
        first_token = registered.json()["access_token"]

        # A second login creates an independent session for the same account.
        logged_in = client.post("/v1/auth/login", json={
            "email": "session@example.com",
            "password": "a-long-secure-password",
        })
        assert logged_in.status_code == 200
        second_token = logged_in.json()["access_token"]
        assert first_token != second_token

        logged_out = client.post(
            "/v1/auth/logout",
            headers={"Authorization": f"Bearer {first_token}"},
        )
        assert logged_out.status_code == 200
        assert logged_out.json() == {"revoked": True}

        revoked_profile = client.get(
            "/v1/users/me",
            headers={"Authorization": f"Bearer {first_token}"},
        )
        assert revoked_profile.status_code == 401

        other_session_profile = client.get(
            "/v1/users/me",
            headers={"Authorization": f"Bearer {second_token}"},
        )
        assert other_session_profile.status_code == 200


def test_logout_requires_a_valid_access_session():
    with TestClient(app) as client:
        assert client.post("/v1/auth/logout").status_code == 401


def test_expired_server_session_rejects_a_still_signed_access_token():
    with TestClient(app) as client:
        registered = client.post("/v1/auth/register", json={
            "email": "expired@example.com",
            "username": "expired_user",
            "password": "a-long-secure-password",
            "display_name": "Expired User",
        })
        assert registered.status_code == 201
        token = registered.json()["access_token"]
        token_id = decode_access_token_claims(token)["jti"]

        with SessionLocal() as db:
            session = db.query(AuthSession).filter(AuthSession.jti == token_id).one()
            session.expires_at = datetime.now(timezone.utc) - timedelta(seconds=1)
            db.commit()

        response = client.get("/v1/users/me", headers={"Authorization": f"Bearer {token}"})
        assert response.status_code == 401


def test_active_sessions_can_be_listed_and_revoked_individually():
    with TestClient(app) as client:
        first_login = client.post("/v1/auth/register", json={
            "email": "devices@example.com",
            "username": "devices_user",
            "password": "a-long-secure-password",
            "display_name": "Devices User",
        })
        assert first_login.status_code == 201
        first_token = first_login.json()["access_token"]

        second_login = client.post("/v1/auth/login", json={
            "email": "devices@example.com",
            "password": "a-long-secure-password",
        })
        assert second_login.status_code == 200
        second_token = second_login.json()["access_token"]

        other_user = client.post("/v1/auth/register", json={
            "email": "other@example.com",
            "username": "other_devices",
            "password": "a-long-secure-password",
            "display_name": "Other User",
        })
        assert other_user.status_code == 201
        other_token = other_user.json()["access_token"]

        sessions_response = client.get(
            "/v1/auth/sessions",
            headers={"Authorization": f"Bearer {first_token}"},
        )
        assert sessions_response.status_code == 200
        sessions = sessions_response.json()
        assert len(sessions) == 2
        current = [item for item in sessions if item["is_current"]]
        remote = [item for item in sessions if not item["is_current"]]
        assert len(current) == 1
        assert len(remote) == 1
        assert "jti" not in current[0]
        assert "access_token" not in current[0]

        remote_session_id = remote[0]["id"]
        forbidden = client.delete(
            f"/v1/auth/sessions/{remote_session_id}",
            headers={"Authorization": f"Bearer {other_token}"},
        )
        assert forbidden.status_code == 404

        revoked = client.delete(
            f"/v1/auth/sessions/{remote_session_id}",
            headers={"Authorization": f"Bearer {first_token}"},
        )
        assert revoked.status_code == 200
        assert revoked.json() == {
            "revoked": True,
            "session_id": remote_session_id,
        }

        remote_profile = client.get(
            "/v1/users/me",
            headers={"Authorization": f"Bearer {second_token}"},
        )
        assert remote_profile.status_code == 401

        current_profile = client.get(
            "/v1/users/me",
            headers={"Authorization": f"Bearer {first_token}"},
        )
        assert current_profile.status_code == 200

        remaining = client.get(
            "/v1/auth/sessions",
            headers={"Authorization": f"Bearer {first_token}"},
        )
        assert remaining.status_code == 200
        assert len(remaining.json()) == 1
        assert remaining.json()[0]["is_current"] is True


def test_active_session_list_requires_authentication():
    with TestClient(app) as client:
        assert client.get("/v1/auth/sessions").status_code == 401
