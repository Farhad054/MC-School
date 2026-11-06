package com.mcschool.flashcard.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mcschool.flashcard.users.User;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EmailNotificationServiceTest {

    private final User teacher = User.invitedTeacher("Teacher", "teacher@test.local",
            "teacher-token", Instant.now().plusSeconds(3600));
    private final User student = User.invitedStudent("Student", "student@test.local", teacher,
            "student-token", Instant.now().plusSeconds(3600));

    @Test
    @SuppressWarnings("unchecked")
    void invitationIsSentThroughBrevoApi() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(201);
        when(response.body()).thenReturn("{\"messageId\":\"message-id\"}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        EmailNotificationService service = new EmailNotificationService(
                httpClient, new AppLinks("http://frontend.test"), "sender@test.local", "brevo-key");

        service.sendInvitation(student, "student-token");

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any(HttpResponse.BodyHandler.class));
        HttpRequest request = captor.getValue();
        String body = bodyAsString(request);
        assertThat(request.uri().toString()).isEqualTo("https://api.brevo.com/v3/smtp/email");
        assertThat(request.timeout()).isEqualTo(Optional.of(Duration.ofSeconds(10)));
        assertThat(request.headers().firstValue("api-key")).contains("brevo-key");
        assertThat(request.headers().firstValue("content-type")).contains("application/json");
        assertThat(body).contains("\"sender\":{\"email\":\"sender@test.local\"}");
        assertThat(body).contains("\"to\":[{\"email\":\"student@test.local\",\"name\":\"Student\"}]");
        assertThat(body).contains("\"subject\":\"Добро пожаловать в Mindcraft School\"");
        assertThat(body).contains("\"textContent\":");
    }

    @Test
    @SuppressWarnings("unchecked")
    void apiErrorIsLoggedAndNotRethrown() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(401);
        when(response.body()).thenReturn("{\"message\":\"unauthorized\"}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        EmailNotificationService service = new EmailNotificationService(
                httpClient, new AppLinks("http://frontend.test"), "sender@test.local", "brevo-key");

        assertThatNoException().isThrownBy(() -> service.sendInvitation(student, "student-token"));

        verify(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void networkFailureIsLoggedAndNotRethrown() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("timeout"));
        EmailNotificationService service = new EmailNotificationService(
                httpClient, new AppLinks("http://frontend.test"), "sender@test.local", "brevo-key");

        assertThatNoException().isThrownBy(() -> service.sendInvitation(student, "student-token"));

        verify(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    private String bodyAsString(HttpRequest request) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                bytes.writeBytes(chunk);
            }

            @Override
            public void onError(Throwable throwable) {
                throw new AssertionError(throwable);
            }

            @Override
            public void onComplete() {
            }
        });
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
