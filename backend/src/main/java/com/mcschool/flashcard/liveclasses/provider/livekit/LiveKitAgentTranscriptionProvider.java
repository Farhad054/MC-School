package com.mcschool.flashcard.liveclasses.provider.livekit;

import com.mcschool.flashcard.liveclasses.OnlineClassProperties;
import com.mcschool.flashcard.liveclasses.provider.ClassTranscriptionProvider;
import com.mcschool.flashcard.liveclasses.provider.MediaProviderException;
import io.livekit.server.AgentDispatchServiceClient;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import livekit.LivekitAgentDispatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import retrofit2.Call;
import retrofit2.Response;

/**
 * Dispatches the transcription agent into a room on demand.
 *
 * <p>Explicit dispatch is what makes "the worker joins only when transcription
 * is started" true: the agent registers with LiveKit under a name but sits idle
 * until a dispatch names it for a room.
 *
 * <p>No STT credential passes through here — the agent holds its own.
 */
public class LiveKitAgentTranscriptionProvider implements ClassTranscriptionProvider {

    private static final Logger log =
            LoggerFactory.getLogger(LiveKitAgentTranscriptionProvider.class);

    private final OnlineClassProperties properties;
    private final String agentName;
    private final AgentDispatchServiceClient dispatchClient;

    public LiveKitAgentTranscriptionProvider(OnlineClassProperties properties, String agentName) {
        this(properties, agentName, AgentDispatchServiceClient.createClient(
                LiveKitMediaProvider.toHttpUrl(properties.livekitUrl()),
                properties.livekitApiKey(), properties.livekitApiSecret()));
    }

    LiveKitAgentTranscriptionProvider(OnlineClassProperties properties, String agentName,
            AgentDispatchServiceClient dispatchClient) {
        this.properties = properties;
        this.agentName = agentName;
        this.dispatchClient = dispatchClient;
    }

    @Override
    public boolean isConfigured() {
        return properties.isTranscriptionAvailable() && agentName != null && !agentName.isBlank();
    }

    @Override
    public String startTranscription(String roomName, String classId, List<String> languageHints) {
        return startTranscription(roomName, classId, languageHints, null);
    }

    @Override
    public String startTranscription(String roomName, String classId, List<String> languageHints,
            Instant lessonStartedAt) {
        // The agent needs the class id to address its ingestion calls, and the
        // language hints to configure the STT session. Metadata is the only
        // channel for that at dispatch time.
        String metadata = metadataJson(classId, languageHints, lessonStartedAt);

        LivekitAgentDispatch.AgentDispatch dispatch =
                execute(dispatchClient.createDispatch(roomName, agentName, metadata, null, null));
        if (dispatch == null) {
            throw new MediaProviderException("Transcription agent could not be dispatched");
        }
        log.info("Transcription agent dispatched: dispatchId={}", dispatch.getId());
        return dispatch.getId();
    }

    static String metadataJson(String classId, List<String> languageHints, Instant lessonStartedAt) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("classId", classId);
        metadata.put("languages", languageHints == null ? List.of() : languageHints);
        if (lessonStartedAt != null) metadata.put("lessonStartedAtMs", lessonStartedAt.toEpochMilli());
        return new tools.jackson.databind.ObjectMapper().writeValueAsString(metadata);
    }

    @Override
    public boolean isTranscriptionRunning(String roomName, String dispatchId) {
        if (dispatchId == null || dispatchId.isBlank()) return false;
        try {
            Response<List<LivekitAgentDispatch.AgentDispatch>> response =
                    dispatchClient.listDispatch(roomName).execute();
            if (response.isSuccessful() && response.body() != null) {
                return response.body().stream().anyMatch(dispatch -> dispatchId.equals(dispatch.getId()));
            }
            if (response.code() == 404 && isMissingDispatch(response.errorBody())) return false;
            if (response.errorBody() != null) response.errorBody().close();
            throw new MediaProviderException("LiveKit agent dispatch check failed with HTTP " + response.code());
        } catch (java.io.IOException e) {
            throw new MediaProviderException("LiveKit agent dispatch check failed", e);
        }
    }

    @Override
    public void stopTranscription(String roomName, String sessionId) {
        try {
            Response<LivekitAgentDispatch.AgentDispatch> response =
                    dispatchClient.deleteDispatch(roomName, sessionId).execute();
            if (response.isSuccessful()) return;
            if (response.code() == 404 && isMissingDispatch(response.errorBody())) {
                log.debug("Transcription dispatch was already stopped");
                return;
            }
            if (response.errorBody() != null) response.errorBody().close();
            throw new MediaProviderException("LiveKit agent dispatch failed with HTTP " + response.code());
        } catch (java.io.IOException e) {
            throw new MediaProviderException("LiveKit agent dispatch failed", e);
        }
    }

    private static boolean isMissingDispatch(okhttp3.ResponseBody body) {
        if (body == null) return false;
        try (body) {
            byte[] bytes = body.byteStream().readNBytes(4097);
            if (bytes.length > 4096) return false;
            tools.jackson.databind.JsonNode error = new tools.jackson.databind.ObjectMapper().readTree(bytes);
            return error != null && "not_found".equals(error.path("code").asText());
        } catch (java.io.IOException | RuntimeException ignored) {
            return false;
        }
    }

    private static <T> T execute(Call<T> call) {
        try {
            Response<T> response = call.execute();
            if (!response.isSuccessful()) {
                throw new MediaProviderException(
                        "LiveKit agent dispatch failed with HTTP " + response.code());
            }
            return response.body();
        } catch (java.io.IOException e) {
            throw new MediaProviderException("LiveKit agent dispatch failed", e);
        }
    }
}
