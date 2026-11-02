from agent.main import parse_dispatch_metadata

FALLBACK = ["ru", "de"]


def test_reads_the_class_id_and_languages():
    class_id, languages = parse_dispatch_metadata(
        '{"classId":"abc","languages":["de"]}', FALLBACK
    )

    assert class_id == "abc"
    assert languages == ["de"]


def test_malformed_metadata_yields_no_class_id():
    # Without a class id the job is a no-op: better than posting segments to a
    # guessed class.
    assert parse_dispatch_metadata("{not json", FALLBACK) == (None, FALLBACK)
    assert parse_dispatch_metadata("[]", FALLBACK) == (None, FALLBACK)
    assert parse_dispatch_metadata(None, FALLBACK) == (None, FALLBACK)
    assert parse_dispatch_metadata("", FALLBACK) == (None, FALLBACK)


def test_falls_back_when_languages_are_missing_or_malformed():
    assert parse_dispatch_metadata('{"classId":"abc"}', FALLBACK)[1] == FALLBACK
    assert parse_dispatch_metadata('{"classId":"abc","languages":"de"}', FALLBACK)[1] == FALLBACK
    assert parse_dispatch_metadata('{"classId":"abc","languages":[1,2]}', FALLBACK)[1] == FALLBACK


def test_a_non_string_class_id_is_rejected():
    assert parse_dispatch_metadata('{"classId":123}', FALLBACK)[0] is None

def test_final_segments_keep_participant_identity_without_audio_storage():
    from types import SimpleNamespace
    from agent.main import build_final_segment

    utterance = SimpleNamespace(text="Hallo zusammen", start_time=0.0, end_time=2.1,
                                language="de", confidence=0.95)
    segment = build_final_segment("room-1", "student-uuid|tab-1", "Student", utterance, 3000)
    assert segment is not None
    assert segment.participant_identity == "student-uuid|tab-1"
    assert segment.speaker_label == "Student"
    assert segment.text == "Hallo zusammen"
    assert segment.start_ms == 900
    assert segment.end_ms == 3000
    assert segment.to_payload()["language"] == "de"
    assert segment.provider_segment_id == build_final_segment(
        "room-1", "student-uuid|tab-1", "Student", utterance, 3000
    ).provider_segment_id


def test_empty_final_is_not_stored():
    from types import SimpleNamespace
    from agent.main import build_final_segment

    assert build_final_segment("room", "speaker", "Teacher",
                               SimpleNamespace(text=" "), 1500) is None

def test_pinned_livekit_runtime_has_the_apis_used_by_the_worker():
    from livekit import agents, rtc
    from livekit.agents.stt import SpeechEventType
    from livekit.plugins import soniox

    assert hasattr(agents, "AgentServer")
    assert hasattr(agents.AgentServer, "rtc_session")
    assert hasattr(agents.AutoSubscribe, "AUDIO_ONLY")
    assert hasattr(rtc, "AudioStream")
    assert hasattr(rtc.TrackKind, "KIND_AUDIO")
    assert hasattr(soniox, "STTOptions")
    assert SpeechEventType.FINAL_TRANSCRIPT
