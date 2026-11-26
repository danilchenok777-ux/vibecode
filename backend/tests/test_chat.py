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


def test_direct_chat_is_unique_and_members_can_exchange_messages():
    with TestClient(app) as client:
        token_a, user_a = register(client, "alice")
        token_b, user_b = register(client, "bobby")
        token_c, _ = register(client, "charlie")

        opened = client.post(
            f"/v1/conversations/direct/{user_b['id']}",
            headers=auth(token_a),
        )
        assert opened.status_code == 200, opened.text
        conversation = opened.json()
        assert conversation["kind"] == "direct"
        assert conversation["member_ids"] == sorted([user_a["id"], user_b["id"]])

        opened_again = client.post(
            f"/v1/conversations/direct/{user_a['id']}",
            headers=auth(token_b),
        )
        assert opened_again.status_code == 200
        assert opened_again.json()["id"] == conversation["id"]

        sent = client.post(
            f"/v1/conversations/{conversation['id']}/messages",
            headers=auth(token_a),
            json={"content": "Hello from GODHEAD"},
        )
        assert sent.status_code == 201, sent.text
        assert sent.json()["content"] == "Hello from GODHEAD"
        assert sent.json()["sender_id"] == user_a["id"]

        received = client.get(
            f"/v1/conversations/{conversation['id']}/messages",
            headers=auth(token_b),
        )
        assert received.status_code == 200
        assert [m["content"] for m in received.json()["items"]] == ["Hello from GODHEAD"]

        hidden = client.get(
            f"/v1/conversations/{conversation['id']}/messages",
            headers=auth(token_c),
        )
        assert hidden.status_code == 404
        forbidden_send = client.post(
            f"/v1/conversations/{conversation['id']}/messages",
            headers=auth(token_c),
            json={"content": "I should not be able to send here"},
        )
        assert forbidden_send.status_code == 404

        conversations = client.get("/v1/conversations", headers=auth(token_b))
        assert conversations.status_code == 200
        assert conversations.json()[0]["last_message"]["content"] == "Hello from GODHEAD"


def test_group_membership_and_message_cursor_pagination():
    with TestClient(app) as client:
        token_a, user_a = register(client, "owner")
        token_b, user_b = register(client, "member")
        token_c, user_c = register(client, "third")

        created = client.post(
            "/v1/conversations/groups",
            headers=auth(token_a),
            json={"title": "Project team", "member_ids": [user_b["id"], user_c["id"], user_b["id"]]},
        )
        assert created.status_code == 201, created.text
        group = created.json()
        assert group["kind"] == "group"
        assert group["title"] == "Project team"
        assert group["member_ids"] == sorted([user_a["id"], user_b["id"], user_c["id"]])

        for message in ("one", "two", "three"):
            response = client.post(
                f"/v1/conversations/{group['id']}/messages",
                headers=auth(token_b),
                json={"content": message},
            )
            assert response.status_code == 201, response.text

        first_page = client.get(
            f"/v1/conversations/{group['id']}/messages?limit=2",
            headers=auth(token_c),
        )
        assert first_page.status_code == 200
        first = first_page.json()
        assert [m["content"] for m in first["items"]] == ["two", "three"]
        assert first["next_before_id"] == first["items"][0]["id"]

        second_page = client.get(
            f"/v1/conversations/{group['id']}/messages?limit=2&before_id={first['next_before_id']}",
            headers=auth(token_c),
        )
        assert second_page.status_code == 200
        assert [m["content"] for m in second_page.json()["items"]] == ["one"]
        assert second_page.json()["next_before_id"] is None


def test_group_creation_validates_members_and_messages():
    with TestClient(app) as client:
        token, user = register(client, "creator")

        single_member = client.post(
            "/v1/conversations/groups",
            headers=auth(token),
            json={"title": "Only me", "member_ids": []},
        )
        assert single_member.status_code == 422

        unknown_member = client.post(
            "/v1/conversations/groups",
            headers=auth(token),
            json={"title": "Unknown", "member_ids": [999999]},
        )
        assert unknown_member.status_code == 404

        self_chat = client.post(
            f"/v1/conversations/direct/{user['id']}",
            headers=auth(token),
        )
        assert self_chat.status_code == 422

        blank_title = client.post(
            "/v1/conversations/groups",
            headers=auth(token),
            json={"title": "   ", "member_ids": [999999]},
        )
        assert blank_title.status_code == 422
