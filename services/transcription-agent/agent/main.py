"""Soniox transcription worker for all audio tracks in a LiveKit classroom.

Each participant's audio track gets a separate Soniox STT stream, so group
lessons retain accurate participant identities. Only final transcript segments
are posted to the Mindcrafti backend; no raw audio is stored by this service.
"""
from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import threading
import time
from types import SimpleNamespace
from typing import Any

from agent.config import AgentConfig
from agent.health import build_app, start_health_server
from agent.ingestion import FinalSegment, TranscriptIngestionClient, segment_id

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("transcription-agent")
_state: dict[str, Any] = {"registered": False}


class AiohttpPoster:
    def __init__(self, session):
        self._session = session

    async def post(self, url, *, json, headers):
        async with self._session.post(url, json=json, headers=headers) as response:
            return response.status


def parse_dispatch_metadata(raw: str | None, fallback_languages: list[str]) -> tuple[str | None, list[str]]:
    if not raw:
        return None, fallback_languages
    try:
        parsed = json.loads(raw)
    except (ValueError, TypeError):
        log.warning("Ignoring malformed dispatch metadata")
        return None, fallback_languages
    if not isinstance(parsed, dict):
        return None, fallback_languages
    class_id = parsed.get("classId")
    languages = parsed.get("languages") or fallback_languages
    if not isinstance(languages, list) or not all(isinstance(code, str) for code in languages):
        languages = fallback_languages
    return (class_id if isinstance(class_id, str) and class_id else None), languages


def build_final_segment(room_name: str, identity: str, label: str, alternative: Any,
                        elapsed_ms: int) -> FinalSegment | None:
    """Convert one Soniox final into a stable, speaker-attributed payload."""
    text = str(getattr(alternative, "text", "") or "").strip()
    if not text:
        return None
    start_seconds = float(getattr(alternative, "start_time", 0.0) or 0.0)
    end_seconds = float(getattr(alternative, "end_time", 0.0) or 0.0)
    duration_ms = int((end_seconds - start_seconds) * 1000)
    if duration_ms <= 0 or duration_ms > 120_000:
        duration_ms = 1000
    end_ms = max(0, elapsed_ms)
    start_ms = max(0, end_ms - duration_ms)
    stable_hash = hashlib.sha256(text.encode("utf-8")).hexdigest()[:12]
    return FinalSegment(
        provider_segment_id=f"{segment_id(room_name, identity, start_ms, end_ms)}:{stable_hash}",
        participant_identity=identity,
        speaker_label=label or "Участник",
        language=str(getattr(alternative, "language", "") or "") or None,
        start_ms=start_ms,
        end_ms=end_ms,
        text=text,
        confidence=getattr(alternative, "confidence", None),
    )


def _serve_health(port: int) -> None:
    async def serve_forever() -> None:
        runner = await start_health_server(build_app(lambda: dict(_state)), port)
        try:
            await asyncio.Event().wait()
        finally:
            await runner.cleanup()
    asyncio.run(serve_forever())


async def receive_finals(websocket, on_final) -> None:
    """Soniox sends each final token once; publish only completed utterances.

    The explicit finished response also flushes words spoken immediately before
    a track/lesson closes, even if no automatic endpoint was detected.
    """
    tokens: list[dict] = []

    async def publish() -> None:
        if not tokens:
            return
        final_tokens = list(tokens)
        tokens.clear()
        text = "".join(str(token.get("text", "")) for token in final_tokens).strip()
        if text:
            confidences = [token["confidence"] for token in final_tokens
                           if isinstance(token.get("confidence"), (int, float))]
            await on_final(SimpleNamespace(
                text=text, start_time=float(final_tokens[0].get("start_ms", 0)) / 1000,
                end_time=float(final_tokens[-1].get("end_ms", 0)) / 1000,
                language=next((t.get("language") for t in final_tokens if t.get("language")), None),
                confidence=sum(confidences) / len(confidences) if confidences else None,
            ))

    try:
        async for message in websocket:
            # Parsing failures and provider errors restart only the affected track.
            content = json.loads(message.data)
            if content.get("error_code") or content.get("error_message"):
                raise RuntimeError("Soniox rejected an audio stream")
            for token in content.get("tokens", []):
                if not token.get("is_final"):
                    continue
                if token.get("text") in ("<end>", "<fin>"):
                    await publish()
                else:
                    tokens.append(token)
            if content.get("finished"):
                await publish()
                return
        raise RuntimeError("Soniox closed before confirming the final audio")
    except Exception:
        # A lost endpoint must not discard words the provider already finalized.
        # Persist that partial utterance before the track starts a fresh stream.
        if tokens:
            await asyncio.wait_for(publish(), timeout=10)
        raise


async def stream_audio(audio, websocket, stop: asyncio.Event, on_final) -> None:
    """Drain a track before closing its STT connection (no SDK private APIs).

    The pinned LiveKit Soniox plugin does not forward end_input/flush to the
    provider. Send the documented finalize and empty-frame messages directly.
    """
    async def send_audio() -> None:
        async for event in audio:
            await websocket.send_bytes(event.frame.data.tobytes())

    async def keepalive() -> None:
        while True:
            await asyncio.sleep(5)
            await websocket.send_json({"type": "keepalive"})

    sender = asyncio.create_task(send_audio())
    receiver = asyncio.create_task(receive_finals(websocket, on_final))
    stopper = asyncio.create_task(stop.wait())
    heartbeat = asyncio.create_task(keepalive())
    tasks = (sender, receiver, stopper, heartbeat)
    try:
        done, _ = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
        if receiver in done:
            await receiver
            return
        if heartbeat in done:
            await heartbeat
        if sender.done():
            await sender
        else:
            sender.cancel()
            await asyncio.gather(sender, return_exceptions=True)
        await audio.aclose()
        heartbeat.cancel()
        await asyncio.gather(heartbeat, return_exceptions=True)
        # FIFO: all accepted PCM frames precede finalization and end-of-audio.
        await websocket.send_json({"type": "finalize"})
        await websocket.send_str("")
        await asyncio.wait_for(receiver, timeout=12)
    finally:
        for task in tasks:
            if not task.done():
                task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        await audio.aclose()


async def run_classroom(ctx, config, *, agents, rtc, aiohttp) -> None:
    class_id, languages = parse_dispatch_metadata(
        getattr(ctx.job, "metadata", None), config.default_languages)
    if not class_id:
        log.warning("Transcription dispatch had no valid classId; skipping")
        ctx.shutdown(reason="invalid transcription metadata")
        return

    started_at = time.monotonic()
    active: dict[str, tuple[asyncio.Task, asyncio.Event]] = {}
    closing = False
    cleanup_task = None
    http = aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=10, connect=5))
    ingestion = TranscriptIngestionClient(
        config.backend_base_url, config.internal_token, AiohttpPoster(http))

    async def process_track(track, participant, stop) -> None:
        backoff = 1
        while not stop.is_set():
            stream_offset_ms = int((time.monotonic() - started_at) * 1000)

            async def on_final(alternative) -> None:
                payload = build_final_segment(
                    ctx.room.name, participant.identity, participant.name or "Участник",
                    alternative, stream_offset_ms + int(alternative.end_time * 1000))
                if payload is not None:
                    await ingestion.submit(class_id, payload)

            audio = rtc.AudioStream(track, sample_rate=16000, num_channels=1)
            try:
                async with http.ws_connect(config.soniox_base_url) as websocket:
                    await websocket.send_json({
                        "api_key": config.soniox_api_key, "model": "stt-rt-v5",
                        "audio_format": "pcm_s16le", "sample_rate": 16000,
                        "num_channels": 1, "language_hints": languages,
                        "enable_language_identification": True,
                        "enable_endpoint_detection": True,
                    })
                    await stream_audio(audio, websocket, stop, on_final)
                return
            except asyncio.CancelledError:
                raise
            except Exception:
                # Do not include provider responses, API keys or lesson text.
                log.warning("Soniox audio stream failed; retrying affected track")
                try:
                    await asyncio.wait_for(stop.wait(), timeout=backoff)
                except asyncio.TimeoutError:
                    backoff = min(backoff * 2, 10)
            finally:
                await audio.aclose()

    def track_subscribed(track, publication, participant) -> None:
        if closing or track.kind != rtc.TrackKind.KIND_AUDIO:
            return
        previous = active.get(publication.sid)
        if previous and not previous[1].is_set():
            return
        stop = asyncio.Event()

        async def run() -> None:
            # A reconnect can reuse the publication SID before its old stream
            # has finished draining. Retain both lifetimes and start the new one.
            if previous:
                await asyncio.gather(previous[0], return_exceptions=True)
            if not stop.is_set():
                await process_track(track, participant, stop)

        task = asyncio.create_task(run())
        active[publication.sid] = (task, stop)

        def done(completed) -> None:
            if active.get(publication.sid, (None,))[0] is completed:
                active.pop(publication.sid, None)
            if not completed.cancelled() and completed.exception():
                log.warning("Transcription audio task failed")
        task.add_done_callback(done)

    def track_unsubscribed(track, publication, participant) -> None:
        entry = active.get(publication.sid)
        if entry:
            entry[1].set()

    async def drain() -> None:
        nonlocal closing
        closing = True
        ctx.room.off("track_subscribed", track_subscribed)
        ctx.room.off("track_unsubscribed", track_unsubscribed)
        entries = list(active.values())
        for _, stop in entries:
            stop.set()
        try:
            if entries:
                _, pending = await asyncio.wait([task for task, _ in entries], timeout=16)
                if pending:
                    log.warning("Timed out draining Soniox final transcripts")
                    for task in pending:
                        task.cancel()
                    await asyncio.gather(*pending, return_exceptions=True)
        finally:
            # Keep HTTP ingestion available until the last final is persisted.
            await http.close()

    async def cleanup() -> None:
        nonlocal cleanup_task
        if cleanup_task is None:
            cleanup_task = asyncio.create_task(drain())
        await asyncio.shield(cleanup_task)

    ctx.room.on("track_subscribed", track_subscribed)
    ctx.room.on("track_unsubscribed", track_unsubscribed)
    ctx.add_shutdown_callback(cleanup)
    try:
        await ctx.connect(auto_subscribe=agents.AutoSubscribe.AUDIO_ONLY)
        log.info("Soniox started for classId=%s", class_id)
    except BaseException:
        await cleanup()
        raise
    # Returning is deliberate: LiveKit keeps the connected job alive and invokes
    # its shutdown callbacks. Waiting forever delays shutdown until cancellation.


async def entrypoint(ctx) -> None:
    """Importable job entrypoint for LiveKit's forkserver/spawn processes."""
    import aiohttp
    from livekit import agents, rtc

    # Each job runs in its own process, so initialize its configuration there.
    await run_classroom(ctx, AgentConfig.from_env(), agents=agents, rtc=rtc, aiohttp=aiohttp)


def main() -> None:
    config = AgentConfig.from_env()
    threading.Thread(target=_serve_health, args=(config.health_port,), daemon=True).start()
    from livekit import agents

    # Longer than the 16-second final-transcript drain: the SDK otherwise kills
    # a job after its default 10 seconds, before our shutdown callback finishes.
    server = agents.AgentServer(shutdown_process_timeout=30)

    @server.on("worker_registered")
    def registered(worker_id, server_info) -> None:
        # Jobs run in child processes; readiness belongs to the parent worker.
        _state["registered"] = True

    server.rtc_session(agent_name=config.agent_name)(entrypoint)

    try:
        agents.cli.run_app(server)
    finally:
        _state["registered"] = False


if __name__ == "__main__":
    main()
