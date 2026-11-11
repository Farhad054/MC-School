"""Production process boundaries: a healthy parent must also be able to spawn jobs."""
import pickle
import sys
import asyncio
import json
from types import SimpleNamespace

import pytest

from agent import main as worker


def test_production_worker_registers_an_importable_entrypoint_with_time_to_drain(monkeypatch):
    started = []

    class Server:
        def __init__(self, *, shutdown_process_timeout):
            self.shutdown_process_timeout = shutdown_process_timeout

        def on(self, _event):
            return lambda callback: callback

        def rtc_session(self, *, agent_name):
            def register(callback):
                self.entrypoint = callback
                self.agent_name = agent_name
                return callback
            return register

    def run_app(server):
        # multiprocessing's forkserver/spawn uses the standard pickler, which
        # rejects nested functions even when the parent health endpoint works.
        restored = pickle.loads(pickle.dumps(server.entrypoint))
        assert restored is worker.entrypoint
        assert server.agent_name == "transcriber"
        assert server.shutdown_process_timeout > 16
        started.append(server)

    monkeypatch.setattr(worker.AgentConfig, "from_env", lambda: SimpleNamespace(
        agent_name="transcriber", health_port=8090))
    monkeypatch.setattr(worker.threading, "Thread", lambda **kw: SimpleNamespace(start=lambda: None))
    monkeypatch.setitem(sys.modules, "livekit", SimpleNamespace(agents=SimpleNamespace(
        AgentServer=Server, cli=SimpleNamespace(run_app=run_app))))

    worker.main()
    assert len(started) == 1


@pytest.mark.parametrize("provider_error", [False, True])
def test_final_words_survive_a_socket_close_or_provider_error_before_endpoint(provider_error):
    async def scenario():
        saved = []

        async def messages():
            yield SimpleNamespace(data=json.dumps({"tokens": [
                {"text": "Final words", "is_final": True, "start_ms": 20, "end_ms": 700},
                {"text": " unfinished", "is_final": False},
            ]}))
            if provider_error:
                yield SimpleNamespace(data=json.dumps({"error_code": 503,
                                                       "error_message": "temporary failure"}))

        async def persist(alternative):
            saved.append(alternative.text)

        with pytest.raises(RuntimeError):
            await worker.receive_finals(messages(), persist)
        assert saved == ["Final words"]

    asyncio.run(scenario())
