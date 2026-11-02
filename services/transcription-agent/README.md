# Transcription agent

A **separate service** from the MC-School backend. It joins a LiveKit room
automatically after the teacher connects to the classroom, transcribes each
participant's audio, and posts final segments back to the backend.

It is deliberately isolated:

- its own dependencies and lockfile — nothing here is on the backend's classpath;
- its own container and health check;
- `SONIOX_API_KEY` lives **only here**. It never reaches the backend, and never
  reaches a browser;
- it authenticates to the backend with a narrow internal token that authorizes
  transcript ingestion and nothing else — never an end-user JWT.

## How it is triggered

The agent registers with LiveKit under `LIVEKIT_AGENT_NAME` and then idles.
After the teacher's browser connects, it requests transcription automatically.
The backend verifies the teacher is present in LiveKit before issuing an
**explicit dispatch**. Opening the lesson, lobby or prejoin screen does not
start transcription; a connection token alone is insufficient.

An active dispatch is reused when the teacher reconnects or opens another tab.
Previously saved text remains attached to the same lesson. Explicitly ending
the lesson stops the dispatch. Metadata carries the `classId` and language hints.

## Configuration

| Variable | Required | Purpose |
|---|---|---|
| `LIVEKIT_URL` | yes | `wss://…` project URL |
| `LIVEKIT_API_KEY` / `LIVEKIT_API_SECRET` | yes | agent registration |
| `LIVEKIT_AGENT_NAME` | yes | must match `ONLINE_CLASS_TRANSCRIPTION_AGENT` in the backend |
| `SONIOX_API_KEY` | yes | STT provider credential — this service only |
| `SONIOX_BASE_URL` | no | EU Soniox endpoint by default |
| `MCSCHOOL_API_BASE_URL` | yes | e.g. `http://backend:8080/api/v1` |
| `TRANSCRIPTION_INTERNAL_TOKEN` | yes | must match the backend value |
| `TRANSCRIPTION_LANGUAGES` | no | default hints, e.g. `ru,de` |
| `HEALTH_PORT` | no | health endpoint port, default 8090 |

## Running

```bash
pip install -r requirements.txt
python -m agent.main start
```

Health: `GET http://localhost:8090/healthz` → `{"status":"ok"}`.

## Tests

```bash
pip install -r requirements-dev.txt
pytest
```

The tests cover participant attribution, track shutdown, the last unfinished
utterance, idempotency keys and retries without contacting LiveKit or Soniox.

Each audio track uses its own Soniox WebSocket with 16 kHz mono PCM. On track
unsubscription or job shutdown, the worker stops the audio reader, sends Soniox
`finalize` followed by end-of-audio, waits for `finished`, persists the remaining
final tokens, and closes the connection. The LiveKit job shutdown callback keeps
the backend HTTP client open until this drain finishes (bounded to 16 seconds).
This uses the documented Soniox protocol because the pinned LiveKit Soniox
plugin does not forward its input-close operation to Soniox.
Protocol references: [manual finalization](https://docs.soniox.com/stt/rt/manual-finalization)
and [ending a WebSocket stream](https://docs.soniox.com/api-reference/stt/websocket-api).

## Failure behaviour

If a Soniox stream fails, the worker reconnects that participant's track with
bounded backoff while other tracks continue. The class keeps running. An agent
process outage requires infrastructure recovery; the backend currently records
dispatch failures, but does not receive a runtime failure heartbeat from the
worker. In a provider/backend outage, audio spoken during the gap and finals
that exceed the bounded retry/drain window cannot be recovered: audio is never
stored.
