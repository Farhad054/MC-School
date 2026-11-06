"""Exercise real task lifetimes with fake audio/Soniox; no provider calls."""
import asyncio
import json
from types import SimpleNamespace

import pytest

from agent.main import receive_finals, run_classroom, stream_audio


class Audio:
    def __init__(self):
        self.frames = asyncio.Queue()
        self.closed = False

    def __aiter__(self):
        return self

    async def __anext__(self):
        return await self.frames.get()

    async def aclose(self):
        self.closed = True


class WebSocket:
    def __init__(self, word="last words"):
        self.messages = asyncio.Queue()
        self.sent = []
        self.audio_sent = asyncio.Event()
        self.word = word
        self.closed = False

    def __aiter__(self):
        return self

    async def __anext__(self):
        return SimpleNamespace(data=json.dumps(await self.messages.get()))

    async def send_bytes(self, data):
        self.sent.append(data)
        self.audio_sent.set()

    async def send_json(self, data):
        self.sent.append(data)

    async def send_str(self, data):
        self.sent.append(data)
        assert self.sent[-2] == {"type": "finalize"}
        # Last phrase has no endpoint; it appears only after end-of-audio.
        await self.messages.put({"tokens": [
            {"text": self.word, "is_final": True, "start_ms": 100, "end_ms": 450,
             "language": "de", "confidence": 0.9},
        ], "finished": True})

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        self.closed = True


def test_stopping_a_track_persists_its_last_unfinished_phrase_and_closes_audio():
    async def scenario():
        audio, websocket, stop = Audio(), WebSocket(), asyncio.Event()
        saved = []

        async def persist(alternative):
            assert not websocket.closed
            saved.append(alternative.text)

        task = asyncio.create_task(stream_audio(audio, websocket, stop, persist))
        await audio.frames.put(SimpleNamespace(frame=SimpleNamespace(data=memoryview(b"pcm"))))
        await websocket.audio_sent.wait()
        stop.set()
        await asyncio.wait_for(task, 1)
        assert saved == ["last words"]
        assert websocket.sent == [b"pcm", {"type": "finalize"}, ""]
        assert audio.closed
    asyncio.run(scenario())


def test_interim_tokens_and_endpoint_markers_are_not_saved_or_duplicated():
    async def scenario():
        websocket, saved = WebSocket(), []

        async def persist(alternative):
            saved.append(alternative.text)

        await websocket.messages.put({"tokens": [
            {"text": "wrong", "is_final": False},
            {"text": "Hallo", "is_final": True},
            {"text": "<end>", "is_final": True},
        ]})
        await websocket.messages.put({"tokens": [{"text": "<fin>", "is_final": True}],
                                      "finished": True})
        await receive_finals(websocket, persist)
        assert saved == ["Hallo"]
    asyncio.run(scenario())


@pytest.mark.parametrize("lesson_age_ms", [None, 120_000])
def test_shutdown_drains_all_existing_and_late_participants_before_closing_http(monkeypatch, lesson_age_ms):
    wall_ms = 1_700_000_000_000
    monkeypatch.setattr("agent.main.time.time", lambda: wall_ms / 1000)

    async def scenario():
        class Room:
            name = "lesson-room"

            def __init__(self):
                self.handlers = {}

            def on(self, event, callback):
                self.handlers[event] = callback

            def off(self, event, callback):
                assert self.handlers.pop(event) == callback

            def join(self, identity):
                self.handlers["track_subscribed"](
                    SimpleNamespace(kind="audio", identity=identity),
                    SimpleNamespace(sid=identity),
                    SimpleNamespace(identity=identity, name=identity))

        class Response:
            status = 200

            async def __aenter__(self):
                return self

            async def __aexit__(self, *args):
                pass

        class Http:
            def __init__(self, **kwargs):
                self.closed = False
                self.sockets = []
                self.saved = []

            def ws_connect(self, url):
                websocket = WebSocket()
                self.sockets.append(websocket)
                return websocket

            def post(self, url, *, json, headers):
                assert not self.closed
                self.saved.append((url, json))
                return Response()

            async def close(self):
                assert all(socket.closed for socket in self.sockets)
                self.closed = True

        http, room, callbacks, audios = Http(), Room(), [], []

        def audio_stream(track, **kwargs):
            assert kwargs == {"sample_rate": 16000, "num_channels": 1}
            audio = Audio()
            audio.frames.put_nowait(SimpleNamespace(frame=SimpleNamespace(data=memoryview(b"pcm"))))
            audios.append(audio)
            return audio

        async def connect(**kwargs):
            room.join("teacher")  # Existing track emitted during connect.
            room.join("student-1")

        metadata = {"classId": "class-1"}
        if lesson_age_ms is not None:
            metadata["lessonStartedAtMs"] = wall_ms - lesson_age_ms
        ctx = SimpleNamespace(room=room, connect=connect, add_shutdown_callback=callbacks.append,
                              job=SimpleNamespace(id="job-current", metadata=json.dumps(metadata)))
        config = SimpleNamespace(default_languages=["de"], backend_base_url="https://backend/api/v1",
                                 internal_token="internal", soniox_base_url="wss://eu/soniox",
                                 soniox_api_key="soniox")
        await run_classroom(ctx, config,
                            agents=SimpleNamespace(AutoSubscribe=SimpleNamespace(AUDIO_ONLY="audio")),
                            rtc=SimpleNamespace(AudioStream=audio_stream,
                                                TrackKind=SimpleNamespace(KIND_AUDIO="audio")),
                            aiohttp=SimpleNamespace(ClientSession=lambda **kw: http,
                                                    ClientTimeout=lambda **kw: kw))
        room.join("student-2")  # Late participant must get an independent stream.
        while len(http.sockets) < 3:
            await asyncio.sleep(0)
        await asyncio.gather(*(socket.audio_sent.wait() for socket in http.sockets))
        room.handlers["track_unsubscribed"](
            None, SimpleNamespace(sid="student-1"), None)
        room.join("student-1")  # Same publication SID while old finals drain.
        while len(http.sockets) < 4:
            await asyncio.sleep(0)
        await http.sockets[-1].audio_sent.wait()
        # A wall-clock correction after the job started must not move finals
        # by an hour. The job uses its original wall offset + monotonic time.
        monkeypatch.setattr("agent.main.time.time", lambda: wall_ms / 1000 + 3600)
        assert not http.closed
        await asyncio.wait_for(callbacks[0](), 1)
        assert http.closed and all(audio.closed for audio in audios)
        assert {payload["participantIdentity"] for _, payload in http.saved} == {
            "teacher", "student-1", "student-2"}
        assert all(url.endswith("/class-1/transcript-segments") for url, _ in http.saved)
        assert all(payload["text"] == "last words" for _, payload in http.saved)
        assert all(payload["providerSegmentId"].startswith("job-current:")
                   for _, payload in http.saved)
        assert all((lesson_age_ms or 0) + 100 <= payload["startMs"]
                   < (lesson_age_ms or 0) + 1000 for _, payload in http.saved)
        assert len(http.saved) == 4
        await callbacks[0]()  # Shutdown callback is safe when repeated.
    asyncio.run(scenario())
