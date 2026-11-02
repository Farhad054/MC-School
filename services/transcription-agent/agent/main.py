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


def main() -> None:
    config = AgentConfig.from_env()

    # Liveness must work before the worker receives its first dispatched class.
    threading.Thread(target=_serve_health, args=(config.health_port,), daemon=True).start()

    # These packages are deliberately imported only at worker startup so
    # metadata and ingestion unit tests don't require the LiveKit SDK.
    import aiohttp
    from livekit import agents, rtc
    from livekit.agents.stt import SpeechEventType
    from livekit.plugins import soniox

    server = agents.AgentServer()

    @server.rtc_session(agent_name=config.agent_name)
    async def entrypoint(ctx: agents.JobContext) -> None:
        class_id, languages = parse_dispatch_metadata(
            getattr(ctx.job, "metadata", None), config.default_languages)
        if not class_id:
            log.warning("Transcription dispatch had no valid classId; skipping")
            return

        started_at = time.monotonic()
        active: dict[str, asyncio.Task] = {}
        async with aiohttp.ClientSession() as http:
            ingestion = TranscriptIngestionClient(
                config.backend_base_url, config.internal_token, AiohttpPoster(http))

            async def process_track(track: rtc.RemoteAudioTrack, participant: rtc.RemoteParticipant) -> None:
                stt = soniox.STT(api_key=config.soniox_api_key,
                                 params=soniox.STTOptions(language_hints=languages))
                audio = rtc.AudioStream(track)
                async with stt.stream() as speech_stream:
                    async def receive() -> None:
                        async for event in speech_stream:
                            if event.type != SpeechEventType.FINAL_TRANSCRIPT or not event.alternatives:
                                continue
                            alternative = event.alternatives[0]
                            payload = build_final_segment(
                                ctx.room.name, participant.identity, participant.name or "Участник",
                                alternative, int((time.monotonic() - started_at) * 1000))
                            if payload is not None:
                                await ingestion.submit(class_id, payload)

                    receiver = asyncio.create_task(receive())
                    try:
                        async for audio_event in audio:
                            speech_stream.push_frame(audio_event.frame)
                    finally:
                        try:
                            speech_stream.end_input()
                        except RuntimeError:
                            pass
                        try:
                            await asyncio.wait_for(receiver, timeout=12)
                        except asyncio.TimeoutError:
                            receiver.cancel()
                            await asyncio.gather(receiver, return_exceptions=True)

            @ctx.room.on("track_subscribed")
            def track_subscribed(track, publication, participant) -> None:
                if track.kind != rtc.TrackKind.KIND_AUDIO:
                    return
                track_id = publication.sid
                if track_id in active:
                    return
                task = asyncio.create_task(process_track(track, participant))
                active[track_id] = task

                def done(completed: asyncio.Task) -> None:
                    active.pop(track_id, None)
                    if not completed.cancelled() and completed.exception():
                        # Never log the lesson content or any provider payload.
                        log.warning("Soniox failed on an audio track; other tracks continue")
                task.add_done_callback(done)

            async def cleanup() -> None:
                ctx.room.off("track_subscribed", track_subscribed)
                # Closing the room ends AudioStreams; permit a short grace
                # interval for Soniox to deliver final results.
                if active:
                    done, pending = await asyncio.wait(active.values(), timeout=14)
                    for task in pending:
                        task.cancel()
                    if pending:
                        await asyncio.gather(*pending, return_exceptions=True)

            ctx.add_shutdown_callback(cleanup)
            await ctx.connect(auto_subscribe=agents.AutoSubscribe.AUDIO_ONLY)
            _state["registered"] = True
            # The callback is registered before connect(), including for peers
            # already in the room when the teacher starts the lesson.
            log.info("Soniox started for classId=%s", class_id)
            await asyncio.Event().wait()

    agents.cli.run_app(server)


if __name__ == "__main__":
    main()
