import pytest
from fastapi.testclient import TestClient

from app.database import Base, _engine
from app.main import app
from app.security import reset_auth_rate_limits_for_tests


@pytest.fixture(autouse=True)
def clean_database():
    reset_auth_rate_limits_for_tests()
    Base.metadata.drop_all(bind=_engine)
    Base.metadata.create_all(bind=_engine)
    yield
    Base.metadata.drop_all(bind=_engine)
    reset_auth_rate_limits_for_tests()


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

def test_read_cursors_are_member_scoped_and_never_move_backwards():
    with TestClient(app) as client:
        token_a, user_a = register(client, "read_sender")
        token_b, user_b = register(client, "read_receiver")
        token_c, _ = register(client, "read_outsider")

        opened = client.post(
            f"/v1/conversations/direct/{user_b['id']}",
            headers=auth(token_a),
        )
        assert opened.status_code == 200
        conversation_id = opened.json()["id"]

        first = client.post(
            f"/v1/conversations/{conversation_id}/messages",
            headers=auth(token_a),
            json={"content": "First"},
        )
        second = client.post(
            f"/v1/conversations/{conversation_id}/messages",
            headers=auth(token_a),
            json={"content": "Second"},
        )
        assert first.status_code == 201
        assert second.status_code == 201

        unread_before_read = client.get("/v1/conversations", headers=auth(token_b))
        assert unread_before_read.status_code == 200
        assert unread_before_read.json()[0]["unread_count"] == 2

        initial_states = client.get(
            f"/v1/conversations/{conversation_id}/read-states",
            headers=auth(token_a),
        )
        assert initial_states.status_code == 200
        assert len(initial_states.json()) == 2
        assert all(item["last_read_message_id"] is None for item in initial_states.json())

        marked = client.put(
            f"/v1/conversations/{conversation_id}/read",
            headers=auth(token_b),
            json={"last_read_message_id": second.json()["id"]},
        )
        assert marked.status_code == 200, marked.text
        assert marked.json()["last_read_message_id"] == second.json()["id"]
        assert marked.json()["user_id"] == user_b["id"]
        unread_after_read = client.get("/v1/conversations", headers=auth(token_b))
        assert unread_after_read.json()[0]["unread_count"] == 0

        # A stale page from another device must not rewind the cursor.
        stale_mark = client.put(
            f"/v1/conversations/{conversation_id}/read",
            headers=auth(token_b),
            json={"last_read_message_id": first.json()["id"]},
        )
        assert stale_mark.status_code == 200
        assert stale_mark.json()["last_read_message_id"] == second.json()["id"]

        states = client.get(
            f"/v1/conversations/{conversation_id}/read-states",
            headers=auth(token_a),
        )
        assert states.status_code == 200
        by_user = {item["user_id"]: item["last_read_message_id"] for item in states.json()}
        assert by_user[user_a["id"]] is None
        assert by_user[user_b["id"]] == second.json()["id"]

        hidden = client.get(
            f"/v1/conversations/{conversation_id}/read-states",
            headers=auth(token_c),
        )
        assert hidden.status_code == 404
        forbidden = client.put(
            f"/v1/conversations/{conversation_id}/read",
            headers=auth(token_c),
            json={"last_read_message_id": second.json()["id"]},
        )
        assert forbidden.status_code == 404


def test_read_cursor_rejects_message_from_another_conversation():
    with TestClient(app) as client:
        token_a, _ = register(client, "read_owner")
        token_b, user_b = register(client, "read_member")
        token_c, user_c = register(client, "read_other")

        first_chat = client.post(
            f"/v1/conversations/direct/{user_b['id']}",
            headers=auth(token_a),
        )
        assert first_chat.status_code == 200
        first_id = first_chat.json()["id"]

        second_chat = client.post(
            f"/v1/conversations/direct/{user_c['id']}",
            headers=auth(token_b),
        )
        assert second_chat.status_code == 200
        second_id = second_chat.json()["id"]

        outsider_message = client.post(
            f"/v1/conversations/{second_id}/messages",
            headers=auth(token_b),
            json={"content": "Different conversation"},
        )
        assert outsider_message.status_code == 201

        wrong_conversation = client.put(
            f"/v1/conversations/{first_id}/read",
            headers=auth(token_a),
            json={"last_read_message_id": outsider_message.json()["id"]},
        )
        assert wrong_conversation.status_code == 404

def test_conversation_attachment_upload_and_private_download(monkeypatch):
    stored = {}

    def fake_upload(fileobj, object_key, content_type, filename):
        stored[object_key] = {
            "bytes": fileobj.read(),
            "content_type": content_type,
            "filename": filename,
        }

    def fake_delete(object_key):
        stored.pop(object_key, None)

    def fake_download_url(object_key, filename, content_type):
        assert object_key in stored
        assert stored[object_key]["filename"] == filename
        assert stored[object_key]["content_type"] == content_type
        return "https://media.example.test/signed-object"

    monkeypatch.setattr("app.chat_routes.upload_attachment_object", fake_upload)
    monkeypatch.setattr("app.chat_routes.delete_attachment_object", fake_delete)
    monkeypatch.setattr("app.chat_routes.create_attachment_download_url", fake_download_url)
    monkeypatch.setattr("app.chat_routes.uses_local_storage", lambda: False)

    with TestClient(app) as client:
        token_a, user_a = register(client, "media_sender")
        token_b, user_b = register(client, "media_receiver")
        token_c, _ = register(client, "media_outsider")
        opened = client.post(
            f"/v1/conversations/direct/{user_b['id']}",
            headers=auth(token_a),
        )
        assert opened.status_code == 200
        conversation_id = opened.json()["id"]

        uploaded = client.post(
            f"/v1/conversations/{conversation_id}/attachments",
            headers=auth(token_a),
            data={"caption": "Look at this image"},
            files={"file": ("photo.png", b"\x89PNG\r\n\x1a\nprivate-image", "image/png")},
        )
        assert uploaded.status_code == 201, uploaded.text
        message = uploaded.json()
        assert message["content"] == "Look at this image"
        assert message["sender_id"] == user_a["id"]
        assert message["attachment"]["filename"] == "photo.png"
        assert message["attachment"]["content_type"] == "image/png"
        assert message["attachment"]["size_bytes"] > 0
        assert len(stored) == 1

        history = client.get(
            f"/v1/conversations/{conversation_id}/messages",
            headers=auth(token_b),
        )
        assert history.status_code == 200
        assert history.json()["items"][0]["attachment"]["id"] == message["attachment"]["id"]

        download = client.get(
            f"/v1/conversations/{conversation_id}/attachments/{message['attachment']['id']}/download",
            headers=auth(token_b),
        )
        assert download.status_code == 200
        assert download.json()["url"] == "https://media.example.test/signed-object"
        assert download.json()["expires_in"] >= 60

        forbidden = client.get(
            f"/v1/conversations/{conversation_id}/attachments/{message['attachment']['id']}/download",
            headers=auth(token_c),
        )
        assert forbidden.status_code == 404


def test_attachment_upload_rejects_unsupported_and_oversized_files(monkeypatch):
    monkeypatch.setattr("app.chat_routes.upload_attachment_object", lambda *args, **kwargs: None)

    with TestClient(app) as client:
        token_a, _ = register(client, "media_limited_owner")
        token_b, user_b = register(client, "media_limited_member")
        opened = client.post(
            f"/v1/conversations/direct/{user_b['id']}",
            headers=auth(token_a),
        )
        assert opened.status_code == 200
        conversation_id = opened.json()["id"]

        unsupported = client.post(
            f"/v1/conversations/{conversation_id}/attachments",
            headers=auth(token_a),
            files={"file": ("script.txt", b"not an image", "text/plain")},
        )
        assert unsupported.status_code == 415

        bad_signature = client.post(
            f"/v1/conversations/{conversation_id}/attachments",
            headers=auth(token_a),
            files={"file": ("spoofed.png", b"not a PNG", "image/png")},
        )
        assert bad_signature.status_code == 415

        from app import chat_routes
        monkeypatch.setattr(chat_routes, "MAX_ATTACHMENT_BYTES", 3)
        oversized = client.post(
            f"/v1/conversations/{conversation_id}/attachments",
            headers=auth(token_a),
            files={"file": ("photo.png", b"1234", "image/png")},
        )
        assert oversized.status_code == 413

        history = client.get(
            f"/v1/conversations/{conversation_id}/messages",
            headers=auth(token_b),
        )
        assert history.status_code == 200
        assert history.json()["items"] == []


def test_local_attachment_storage_streams_only_for_conversation_members(tmp_path, monkeypatch):
    from app import chat_routes, storage
    from app.config import get_settings

    settings = get_settings().model_copy(update={
        "attachment_storage_backend": "local",
        "local_storage_dir": str(tmp_path / "attachments"),
    })
    monkeypatch.setattr(storage, "get_settings", lambda: settings)
    monkeypatch.setattr(chat_routes, "get_settings", lambda: settings)

    with TestClient(app) as client:
        token_a, _ = register(client, "local_media_sender")
        token_b, user_b = register(client, "local_media_receiver")
        token_c, _ = register(client, "local_media_outsider")
        opened = client.post(
            f"/v1/conversations/direct/{user_b['id']}",
            headers=auth(token_a),
        )
        assert opened.status_code == 200
        conversation_id = opened.json()["id"]
        image = b"\x89PNG\r\n\x1a\nprivate-image-bytes"
        uploaded = client.post(
            f"/v1/conversations/{conversation_id}/attachments",
            headers=auth(token_a),
            files={"file": ("photo.png", image, "image/png")},
        )
        assert uploaded.status_code == 201, uploaded.text
        attachment_id = uploaded.json()["attachment"]["id"]

        metadata = client.get(
            f"/v1/conversations/{conversation_id}/attachments/{attachment_id}/download",
            headers=auth(token_b),
        )
        assert metadata.status_code == 200
        download_path = metadata.json()["url"]
        assert download_path.endswith(f"/{attachment_id}/content")
        assert metadata.json()["expires_in"] == 0

        downloaded = client.get(download_path, headers=auth(token_b))
        assert downloaded.status_code == 200
        assert downloaded.content == image
        assert downloaded.headers["content-type"] == "image/png"

        private = client.get(download_path, headers=auth(token_c))
        assert private.status_code == 404

        local_file = storage.get_local_attachment_path(
            f"conversations/{conversation_id}/missing-object"
        )
        assert not local_file.exists()
        with pytest.raises(ValueError, match="Invalid attachment object key"):
            storage.get_local_attachment_path("../../outside-file")

def test_message_delivery_acknowledgement_is_recipient_only_and_idempotent():
    with TestClient(app) as client:
        token_a, user_a = register(client, "delivery_sender")
        token_b, user_b = register(client, "delivery_receiver")
        token_c, _ = register(client, "delivery_outsider")

        opened = client.post(
            f"/v1/conversations/direct/{user_b['id']}",
            headers=auth(token_a),
        )
        assert opened.status_code == 200
        conversation_id = opened.json()["id"]

        sent = client.post(
            f"/v1/conversations/{conversation_id}/messages",
            headers=auth(token_a),
            json={"content": "Delivered to the recipient"},
        )
        assert sent.status_code == 201
        message_id = sent.json()["id"]

        sender_cannot_ack = client.post(
            f"/v1/conversations/{conversation_id}/messages/{message_id}/delivery",
            headers=auth(token_a),
        )
        assert sender_cannot_ack.status_code == 409

        outsider_cannot_ack = client.post(
            f"/v1/conversations/{conversation_id}/messages/{message_id}/delivery",
            headers=auth(token_c),
        )
        assert outsider_cannot_ack.status_code == 404

        acknowledged = client.post(
            f"/v1/conversations/{conversation_id}/messages/{message_id}/delivery",
            headers=auth(token_b),
        )
        assert acknowledged.status_code == 200, acknowledged.text
        assert acknowledged.json()["message_id"] == message_id
        assert acknowledged.json()["user_id"] == user_b["id"]
        assert acknowledged.json()["delivered_at"] is not None

        # Repeated receipt from a second device is idempotent and preserves
        # the original delivery timestamp.
        acknowledged_again = client.post(
            f"/v1/conversations/{conversation_id}/messages/{message_id}/delivery",
            headers=auth(token_b),
        )
        assert acknowledged_again.status_code == 200
        assert acknowledged_again.json()["delivered_at"] == acknowledged.json()["delivered_at"]

        states = client.get(
            f"/v1/conversations/{conversation_id}/messages/{message_id}/delivery-states",
            headers=auth(token_a),
        )
        assert states.status_code == 200
        assert states.json() == [acknowledged.json()]

        receiver_can_list = client.get(
            f"/v1/conversations/{conversation_id}/messages/{message_id}/delivery-states",
            headers=auth(token_b),
        )
        assert receiver_can_list.status_code == 200
        assert receiver_can_list.json()[0]["user_id"] == user_b["id"]

        outsider_cannot_list = client.get(
            f"/v1/conversations/{conversation_id}/messages/{message_id}/delivery-states",
            headers=auth(token_c),
        )
        assert outsider_cannot_list.status_code == 404

        wrong_message = client.get(
            f"/v1/conversations/{conversation_id}/messages/{message_id + 999}/delivery-states",
            headers=auth(token_a),
        )
        assert wrong_message.status_code == 404


def test_group_delivery_states_show_unacknowledged_members():
    with TestClient(app) as client:
        token_a, user_a = register(client, "delivery_group_owner")
        token_b, user_b = register(client, "delivery_group_b")
        token_c, user_c = register(client, "delivery_group_c")
        group = client.post(
            "/v1/conversations/groups",
            headers=auth(token_a),
            json={"title": "Delivery group", "member_ids": [user_b["id"], user_c["id"]]},
        )
        assert group.status_code == 201
        conversation_id = group.json()["id"]
        sent = client.post(
            f"/v1/conversations/{conversation_id}/messages",
            headers=auth(token_a),
            json={"content": "Group delivery"},
        )
        assert sent.status_code == 201
        message_id = sent.json()["id"]

        acknowledged = client.post(
            f"/v1/conversations/{conversation_id}/messages/{message_id}/delivery",
            headers=auth(token_b),
        )
        assert acknowledged.status_code == 200

        states = client.get(
            f"/v1/conversations/{conversation_id}/messages/{message_id}/delivery-states",
            headers=auth(token_a),
        )
        assert states.status_code == 200
        by_user = {item["user_id"]: item["delivered_at"] for item in states.json()}
        assert set(by_user) == {user_b["id"], user_c["id"]}
        assert by_user[user_b["id"]] is not None
        assert by_user[user_c["id"]] is None

