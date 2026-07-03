import pytest
from unittest.mock import AsyncMock, MagicMock, patch
from fastapi.testclient import TestClient


@pytest.fixture
def client():
    with (
        patch("app.config.Settings", autospec=True),
        patch("app.memory.conversation.get_redis", new_callable=AsyncMock),
        patch("anthropic.AsyncAnthropic"),
    ):
        from app.main import app
        return TestClient(app)


def test_health(client):
    with patch("app.api.health.get_redis", new_callable=AsyncMock) as mock_redis:
        mock_redis.return_value.ping = AsyncMock(return_value=True)
        resp = client.get("/health")
    assert resp.status_code == 200
    assert resp.json()["status"] in ("ok", "degraded")
