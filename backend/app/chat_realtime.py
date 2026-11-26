import asyncio
from collections import defaultdict
from fastapi import WebSocket, WebSocketDisconnect


class ConnectionManager:
    """In-process WebSocket fan-out for connected GODHEAD accounts."""

    def __init__(self):
        self._connections: dict[int, set[WebSocket]] = defaultdict(set)
        self._lock = asyncio.Lock()

    async def connect(self, user_id: int, websocket: WebSocket) -> None:
        await websocket.accept()
        async with self._lock:
            self._connections[user_id].add(websocket)

    async def disconnect(self, user_id: int, websocket: WebSocket) -> None:
        async with self._lock:
            connections = self._connections.get(user_id)
            if connections is None:
                return
            connections.discard(websocket)
            if not connections:
                self._connections.pop(user_id, None)

    async def broadcast(self, user_ids: list[int], event: dict) -> None:
        async with self._lock:
            targets = [
                (user_id, socket)
                for user_id in set(user_ids)
                for socket in self._connections.get(user_id, set())
            ]

        stale: list[tuple[int, WebSocket]] = []
        for user_id, websocket in targets:
            try:
                await websocket.send_json(event)
            except (WebSocketDisconnect, RuntimeError):
                stale.append((user_id, websocket))

        for user_id, websocket in stale:
            await self.disconnect(user_id, websocket)


connection_manager = ConnectionManager()
