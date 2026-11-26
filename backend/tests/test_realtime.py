import pytest
from fastapi.testclient import TestClient

from app.database import Base, _engine
from app.main import app


@pytest.fixture(autouse=True)
def clean_database():
    Base.metadata.drop_all(bind=_engine)
    Base.metadata.create_all(bind=_engine)
    yield
    Base.metadata.drop_all(bind=_engine)


def register(client: TestClient, username: str):
    response = client.post("/v1/auth/register", json={
        "email": f"{username}@example.com",
        "username": username,
        "password": "a-long-secure-password",
        "display_name": username.title(),
    })
    assert response.status_code == 201, response.text
    payload = response.json()
    return payload["access_token"], payload["user"]


def auth(token: str):
    return {"Authorization": f"Bearer {token}"}


def test_websocket_authenticates_and_delivers_new_messages():
    with TestClient(app) as client:
        token_a, user_a = register(client, "sender")
        token_b, user_b = register(client, "receiver")
        opened = client.post(
            f"/v1/conversations/direct/{user_b['id']}",
            headers=auth(token_a),
        )
        assert opened.status_code == 200
        conversation_id = opened.json()["id"]

        with client.websocket_connect("/v1/ws", headers=auth(token_b)) as websocket:
            websocket.send_text("ping")
            assert websocket.receive_json()["type"] == "pong"

            sent = client.post(
                f"/v1/conversations/{conversation_id}/messages",
                headers=auth(token_a),
                json={"content": "Live event"},
            )
            assert sent.status_code == 201
            event = websocket.receive_json()
            assert event["type"] == "message.created"
            assert event["data"]["content"] == "Live event"
            assert event["data"]["sender_id"] == user_a["id"]


def test_websocket_rejects_missing_token():
    with TestClient(app) as client:
        with pytest.raises(Exception):
            with client.websocket_connect("/v1/ws"):
                pass


def test_websocket_rejects_a_revoked_session():
    with TestClient(app) as client:
        token, _ = register(client, "socket_revoked")
        signed_out = client.post("/v1/auth/logout", headers=auth(token))
        assert signed_out.status_code == 200

        # The bearer token is cryptographically valid but no longer represents
        # an active server-side session.
        with pytest.raises(Exception):
            with client.websocket_connect("/v1/ws", headers=auth(token)):
                pass

def test_websocket_broadcasts_read_cursor_updates_to_conversation_members():
    with TestClient(app) as client:
        token_a, _ = register(client, "read_event_sender")
        token_b, user_b = register(client, "read_event_reader")
        opened = client.post(
            f"/v1/conversations/direct/{user_b['id']}",
            headers=auth(token_a),
        )
        assert opened.status_code == 200
        conversation_id = opened.json()["id"]

        sent = client.post(
            f"/v1/conversations/{conversation_id}/messages",
            headers=auth(token_a),
            json={"content": "Please read this"},
        )
        assert sent.status_code == 201

        with client.websocket_connect("/v1/ws", headers=auth(token_a)) as websocket:
            marked = client.put(
                f"/v1/conversations/{conversation_id}/read",
                headers=auth(token_b),
                json={"last_read_message_id": sent.json()["id"]},
            )
            assert marked.status_code == 200, marked.text

            event = websocket.receive_json()
            assert event["type"] == "read.state.updated"
            assert event["data"]["conversation_id"] == conversation_id
            assert event["data"]["user_id"] == user_b["id"]
            assert event["data"]["last_read_message_id"] == sent.json()["id"]

