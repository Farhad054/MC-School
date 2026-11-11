"""Production retry diagnostics expose classifications, never provider payloads."""
import asyncio
import json
import sys
from types import SimpleNamespace

import pytest

from agent import main as worker
from agent.main import SonioxError, run_classroom


@pytest.mark.parametrize("raw_code", [None, True, 503.0, "503", "api-key-secret", 1234567890])
def test_soniox_error_never_retains_an_untrusted_status_value(raw_code):
    error = SonioxError(raw_code)
    assert error.code is None
    assert str(error) == "Soniox rejected an audio stream"


@pytest.mark.parametrize("handshake_failure", [False, True])
def test_retry_logs_only_error_type_and_numeric_status_and_preserves_final_words(caplog, handshake_failure):
    secret = "private-provider-response-with-api-key"
    provider_url = "wss://provider.example/private?api_key=secret"
    transcript = "Private lesson words"

    async def scenario():
        handlers, callbacks, saved, sockets = {}, [], [], []

        class HandshakeFailure(RuntimeError):
            status = 401

        class Audio:
            def __aiter__(self):
                return self

            async def __anext__(self):
                await asyncio.Event().wait()

            async def aclose(self):
                pass

        class Socket:
            def __init__(self):
                self.messages = iter([
                    {"tokens": [{"text": transcript, "is_final": True,
                                 "start_ms": 0, "end_ms": 500}]},
                    {"error_code": 503, "error_message": secret,
                     "more_info": provider_url},
                ])

            async def __aenter__(self):
                if handshake_failure:
                    raise HandshakeFailure(f"{secret} {provider_url} {transcript}")
                return self

            async def __aexit__(self, *args):
                pass

            def __aiter__(self):
                return self

            async def __anext__(self):
                try:
                    content = next(self.messages)
                except StopIteration:
                    raise StopAsyncIteration
                return SimpleNamespace(data=json.dumps(content))

            async def send_json(self, content):
                pass

        class Response:
            status = 200

            async def __aenter__(self):
                return self

            async def __aexit__(self, *args):
                pass

        class Http:
            def ws_connect(self, _url):
                socket = Socket()
                sockets.append(socket)
                return socket

            def post(self, _url, *, json, headers):
                saved.append(json)
                return Response()

            async def close(self):
                pass

        def on(event, callback):
            handlers[event] = callback

        def off(event, callback):
            assert handlers.pop(event) is callback

        async def connect(**kwargs):
            handlers["track_subscribed"](
                SimpleNamespace(kind="audio"), SimpleNamespace(sid="track-1"),
                SimpleNamespace(identity="teacher", name="Teacher"))

        ctx = SimpleNamespace(room=SimpleNamespace(name="room", on=on, off=off),
                              connect=connect, add_shutdown_callback=callbacks.append,
                              job=SimpleNamespace(id="job-1", metadata='{"classId":"class-1"}'))
        config = SimpleNamespace(default_languages=["de"], backend_base_url="https://backend/api/v1",
                                 internal_token="internal-secret", soniox_base_url=provider_url,
                                 soniox_api_key="api-key-secret")
        await run_classroom(ctx, config,
                            agents=SimpleNamespace(AutoSubscribe=SimpleNamespace(AUDIO_ONLY="audio")),
                            rtc=SimpleNamespace(AudioStream=lambda *a, **kw: Audio(),
                                                TrackKind=SimpleNamespace(KIND_AUDIO="audio")),
                            aiohttp=SimpleNamespace(ClientSession=lambda **kw: Http(),
                                                    ClientTimeout=lambda **kw: kw))
        try:
            # Let the actual process_track exception handler log the failure.
            async def logged():
                while not any("retrying affected track" in record.message for record in caplog.records):
                    await asyncio.sleep(0)
            await asyncio.wait_for(logged(), timeout=1)
        finally:
            await callbacks[0]()
        assert len(sockets) == 1  # Shutdown interrupts retry backoff.
        assert [payload["text"] for payload in saved] == ([] if handshake_failure else [transcript])

    with caplog.at_level("WARNING", logger="transcription-agent"):
        asyncio.run(scenario())
    assert ("error=HandshakeFailure status=401" if handshake_failure
            else "error=SonioxError status=503") in caplog.text
    for sensitive in (secret, provider_url, transcript, "api-key-secret", "internal-secret"):
        assert sensitive not in caplog.text
    assert all(record.exc_info is None for record in caplog.records)


@pytest.mark.parametrize("status", [None, 401, 402, 403])
def test_startup_self_test_uses_only_generated_silence_and_reports_a_sanitized_result(caplog, status):
    sent, closed = [], []
    secret = "provider-private-message-api-key"
    config = SimpleNamespace(soniox_base_url="wss://private-endpoint?key=secret",
                             soniox_api_key="secret-key", default_languages=["de", "ru"])

    class Socket:
        async def __aenter__(self):
            return self

        async def __aexit__(self, *args):
            closed.append("socket")

        async def send_json(self, content):
            sent.append(content)

        async def send_bytes(self, content):
            sent.append(content)

        async def send_str(self, content):
            sent.append(content)

        def __aiter__(self):
            async def responses():
                content = ({"finished": True, "tokens": []} if status is None else
                           {"error_code": status, "error_message": secret,
                            "more_info": config.soniox_base_url})
                yield SimpleNamespace(data=json.dumps(content))
            return responses()

    class Http:
        async def __aenter__(self):
            return self

        async def __aexit__(self, *args):
            closed.append("http")

        def ws_connect(self, url):
            assert url == config.soniox_base_url
            return Socket()

        def post(self, *args, **kwargs):
            raise AssertionError("A startup probe must not ingest a lesson transcript")

    with caplog.at_level("INFO", logger="transcription-agent"):
        asyncio.run(worker.check_soniox_startup(config, aiohttp=SimpleNamespace(
            ClientSession=lambda **kw: Http(), ClientTimeout=lambda **kw: kw)))
    assert sent[0]["model"] == "stt-rt-v5"
    assert sent[0]["audio_format"] == "pcm_s16le"
    assert sent[0]["sample_rate"] == 16000 and sent[0]["num_channels"] == 1
    assert sent[1] == bytes(8000) and sent[2] == ""
    assert closed == ["socket", "http"]
    assert worker._state["soniox_ready"] is (status is None)
    if status is None:
        assert "soniox_code" not in worker._state
        assert "Soniox startup self-test succeeded" in caplog.text
    else:
        assert worker._state["soniox_code"] == status
        assert f"error=SonioxError status={status}" in caplog.text
    for sensitive in (secret, config.soniox_base_url, config.soniox_api_key):
        assert sensitive not in caplog.text
    assert all(record.exc_info is None for record in caplog.records)


def test_repeated_worker_registration_schedules_only_one_nonblocking_startup_probe(monkeypatch):
    checks = []

    class Server:
        def __init__(self, **kw):
            self.callbacks = {}

        def on(self, event):
            def register(callback):
                self.callbacks[event] = callback
                return callback
            return register

        def rtc_session(self, **kw):
            return lambda callback: callback

    async def check(config):
        checks.append(config)

    def run_app(server):
        server.callbacks["worker_registered"]("first", None)
        server.callbacks["worker_registered"]("reconnect", None)
        assert checks == []  # The registration handler does not wait for vendor I/O.

    config = SimpleNamespace(agent_name="transcriber", health_port=8090)
    monkeypatch.setattr(worker.AgentConfig, "from_env", lambda: config)
    monkeypatch.setattr(worker, "check_soniox_startup", check)
    monkeypatch.setattr(worker.threading, "Thread", lambda **kw: SimpleNamespace(start=lambda: None))
    monkeypatch.setitem(sys.modules, "livekit", SimpleNamespace(agents=SimpleNamespace(
        AgentServer=Server, cli=SimpleNamespace(run_app=run_app))))

    async def scenario():
        worker.main()
        await asyncio.sleep(0)
        assert checks == [config]
        await asyncio.sleep(0)  # Run the completed-task callback too.

    asyncio.run(scenario())
