package com.mcschool.flashcard.liveclasses.provider.livekit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mcschool.flashcard.liveclasses.OnlineClassProperties;
import com.mcschool.flashcard.liveclasses.provider.MediaProviderException;
import io.livekit.server.AgentDispatchServiceClient;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import livekit.LivekitAgentDispatch;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import retrofit2.Call;
import retrofit2.Response;
import tools.jackson.databind.ObjectMapper;

/** Provider failures are unknown state, never proof that a duplicate dispatch is safe. */
class LiveKitAgentTranscriptionProviderTest {
    private static final String ROOM = "lesson-room";
    private static final String DISPATCH = "dispatch-1";

    private LiveKitAgentTranscriptionProvider provider(AgentDispatchServiceClient client) {
        return new LiveKitAgentTranscriptionProvider(mock(OnlineClassProperties.class), "transcriber", client);
    }

    private static ResponseBody body(String json) {
        return ResponseBody.create(json, MediaType.get("application/json"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void startingPassesEscapedMetadataAndTheOriginalLessonTimeline() throws Exception {
        AgentDispatchServiceClient client = mock(AgentDispatchServiceClient.class);
        Call<LivekitAgentDispatch.AgentDispatch> operation = mock(Call.class);
        when(client.createDispatch(eq(ROOM), eq("transcriber"), anyString(), isNull(), isNull()))
                .thenReturn(operation);
        when(operation.execute()).thenReturn(Response.success(
                LivekitAgentDispatch.AgentDispatch.newBuilder().setId(DISPATCH).build()));
        Instant origin = Instant.parse("2026-11-02T09:00:00Z");

        assertThat(provider(client).startTranscription(ROOM, "class-\"one\"", List.of("de", "ru"), origin))
                .isEqualTo(DISPATCH);

        ArgumentCaptor<String> metadata = ArgumentCaptor.forClass(String.class);
        verify(client).createDispatch(eq(ROOM), eq("transcriber"), metadata.capture(), isNull(), isNull());
        var json = new ObjectMapper().readTree(metadata.getValue());
        assertThat(json.path("classId").asText()).isEqualTo("class-\"one\"");
        assertThat(json.path("languages").get(0).asText()).isEqualTo("de");
        assertThat(json.path("languages").get(1).asText()).isEqualTo("ru");
        assertThat(json.path("lessonStartedAtMs").asLong()).isEqualTo(origin.toEpochMilli());
        assertThat(new ObjectMapper().readTree(LiveKitAgentTranscriptionProvider.metadataJson(
                "legacy", List.of("de"), null)).has("lessonStartedAtMs")).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void dispatchPresenceUsesOnlyAConfirmedMatchingIdOrConfirmedMissingRoom() throws Exception {
        AgentDispatchServiceClient client = mock(AgentDispatchServiceClient.class);
        Call<List<LivekitAgentDispatch.AgentDispatch>> operation = mock(Call.class);
        when(client.listDispatch(ROOM)).thenReturn(operation);
        var adapter = provider(client);
        when(operation.execute()).thenReturn(Response.success(List.of(
                LivekitAgentDispatch.AgentDispatch.newBuilder().setId(DISPATCH).build())));
        assertThat(adapter.isTranscriptionRunning(ROOM, DISPATCH)).isTrue();
        assertThat(adapter.isTranscriptionRunning(ROOM, "another-dispatch")).isFalse();
        when(operation.execute()).thenReturn(Response.success(List.of()));
        assertThat(adapter.isTranscriptionRunning(ROOM, DISPATCH)).isFalse();
        when(operation.execute()).thenReturn(Response.error(404, body("{\"code\":\"not_found\"}")));
        assertThat(adapter.isTranscriptionRunning(ROOM, DISPATCH)).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aDispatchListOutageOrMalformedResponseDoesNotMeanTheOldJobIsGone() throws Exception {
        AgentDispatchServiceClient client = mock(AgentDispatchServiceClient.class);
        Call<List<LivekitAgentDispatch.AgentDispatch>> operation = mock(Call.class);
        when(client.listDispatch(ROOM)).thenReturn(operation);
        var adapter = provider(client);
        for (int code : List.of(401, 403, 500, 503)) {
            when(operation.execute()).thenReturn(Response.error(code, body("{\"code\":\"unavailable\"}")));
            assertThatThrownBy(() -> adapter.isTranscriptionRunning(ROOM, DISPATCH))
                    .isInstanceOf(MediaProviderException.class);
        }
        for (String invalid : List.of("<html>not found</html>", "{}", "null",
                "{\"code\":\"unavailable\"}", "x".repeat(4097))) {
            when(operation.execute()).thenReturn(Response.error(404, body(invalid)));
            assertThatThrownBy(() -> adapter.isTranscriptionRunning(ROOM, DISPATCH))
                    .isInstanceOf(MediaProviderException.class);
        }
        when(operation.execute()).thenReturn(Response.success(null));
        assertThatThrownBy(() -> adapter.isTranscriptionRunning(ROOM, DISPATCH))
                .isInstanceOf(MediaProviderException.class);
        when(operation.execute()).thenThrow(new IOException("offline"));
        assertThatThrownBy(() -> adapter.isTranscriptionRunning(ROOM, DISPATCH))
                .isInstanceOf(MediaProviderException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void stoppingAcceptsOnlySuccessOrExplicitMissingDispatch() throws Exception {
        AgentDispatchServiceClient client = mock(AgentDispatchServiceClient.class);
        Call<LivekitAgentDispatch.AgentDispatch> operation = mock(Call.class);
        when(client.deleteDispatch(ROOM, DISPATCH)).thenReturn(operation);
        var adapter = provider(client);
        when(operation.execute()).thenReturn(Response.success(null));
        adapter.stopTranscription(ROOM, DISPATCH);
        when(operation.execute()).thenReturn(Response.error(404, body("{\"code\":\"not_found\"}")));
        adapter.stopTranscription(ROOM, DISPATCH);
        for (int code : List.of(401, 403, 500, 503)) {
            when(operation.execute()).thenReturn(Response.error(code, body("{}")));
            assertThatThrownBy(() -> adapter.stopTranscription(ROOM, DISPATCH))
                    .isInstanceOf(MediaProviderException.class);
        }
        when(operation.execute()).thenReturn(Response.error(404, body("<html>not found</html>")));
        assertThatThrownBy(() -> adapter.stopTranscription(ROOM, DISPATCH))
                .isInstanceOf(MediaProviderException.class);
        when(operation.execute()).thenThrow(new IOException("offline"));
        assertThatThrownBy(() -> adapter.stopTranscription(ROOM, DISPATCH))
                .isInstanceOf(MediaProviderException.class);
    }
}
