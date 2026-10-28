package llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The HTTP adapter against a local {@link HttpServer} on the loopback address: what it sends, and
 * that every failure becomes an error response without the key in it. No test uses the real network.
 */
class OpenAiCompatibleClientTest {

    private static final String KEY = "sk-test-0123456789abcdef";
    private static final LlmRequest JSON_REQUEST = new LlmRequest("You answer in JSON.", "go north", true, 300);
    private static final LlmRequest TEXT_REQUEST = new LlmRequest("You narrate.", "The door opens.", false, 50);

    /** A client for the local server, with a short timeout. */
    private static final class LocalClient extends OpenAiCompatibleClient {
        LocalClient(String baseUrl, String apiKey, Duration timeout) {
            super(baseUrl, "test-model", apiKey, timeout);
        }

        @Override
        public String providerName() {
            return "local";
        }
    }

    /** What the server received for one request. */
    private record Received(String method, String path, String authorization, String contentType, String body) {
    }

    private HttpServer server;
    private ExecutorService executor;
    private final List<Received> received = new ArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private volatile int status = 200;
    private volatile String responseBody = answer("{\"actions\":[]}");
    private volatile boolean hang;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            synchronized (received) {
                received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        exchange.getRequestHeaders().getFirst("Content-Type"), body));
            }
            if (hang) {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        release.countDown();
        server.stop(0);
        executor.shutdownNow();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private LocalClient client() {
        return new LocalClient(baseUrl(), KEY, Duration.ofSeconds(5));
    }

    private static String answer(String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("content", content);
        return "{\"id\":\"1\",\"choices\":[{\"index\":0,\"message\":" + message + ",\"finish_reason\":\"stop\"}]}";
    }

    private void respond(int status, String body) {
        this.status = status;
        this.responseBody = body;
    }

    /** Runs the call with System.out and System.err captured, and checks the key never shows up there. */
    private LlmResponse completeAndCheckNoKeyLeaks(Supplier<LlmResponse> call) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(captured, true, StandardCharsets.UTF_8);
        LlmResponse response;
        try {
            System.setOut(capture);
            System.setErr(capture);
            response = call.get();
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        assertFalse(captured.toString(StandardCharsets.UTF_8).contains(KEY), "key printed");
        if (response.getError() != null) {
            assertFalse(response.getError().contains(KEY), response.getError());
        }
        return response;
    }

    @Test
    void sendsAChatCompletionWithTheModelMessagesLimitsAndKey() {
        LlmResponse response = client().complete(JSON_REQUEST);

        assertTrue(response.isOk(), response.getError());
        assertEquals(1, received.size());
        Received request = received.get(0);
        assertEquals("POST", request.method());
        assertEquals("/v1/chat/completions", request.path());
        assertEquals("Bearer " + KEY, request.authorization());
        assertEquals("application/json", request.contentType());
        JsonObject body = JsonParser.parseString(request.body()).getAsJsonObject();
        assertEquals("test-model", body.get("model").getAsString());
        assertEquals(300, body.get("max_tokens").getAsInt());
        assertEquals(OpenAiCompatibleClient.TEMPERATURE, body.get("temperature").getAsDouble());
        assertFalse(body.get("stream").getAsBoolean());
        assertEquals("json_object", body.getAsJsonObject("response_format").get("type").getAsString());
        JsonObject system = body.getAsJsonArray("messages").get(0).getAsJsonObject();
        JsonObject user = body.getAsJsonArray("messages").get(1).getAsJsonObject();
        assertEquals("system", system.get("role").getAsString());
        assertEquals("You answer in JSON.", system.get("content").getAsString());
        assertEquals("user", user.get("role").getAsString());
        assertEquals("go north", user.get("content").getAsString());
    }

    @Test
    void aTextRequestAsksForNoResponseFormat() {
        client().complete(TEXT_REQUEST);

        JsonObject body = JsonParser.parseString(received.get(0).body()).getAsJsonObject();
        assertFalse(body.has("response_format"));
        assertEquals(50, body.get("max_tokens").getAsInt());
    }

    @Test
    void readsTheFirstChoicesMessageContent() {
        respond(200, answer("{\"actions\":[{\"type\":\"move\",\"target\":\"north\"}]}"));

        LlmResponse response = client().complete(JSON_REQUEST);

        assertTrue(response.isOk());
        assertNull(response.getError());
        assertEquals("{\"actions\":[{\"type\":\"move\",\"target\":\"north\"}]}", response.getText());
    }

    @Test
    void aTrailingSlashOnTheBaseUrlIsIgnored() {
        new LocalClient(baseUrl() + "/", KEY, Duration.ofSeconds(5)).complete(JSON_REQUEST);

        assertEquals("/v1/chat/completions", received.get(0).path());
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 429, 500, 502, 503})
    void anErrorStatusIsAFailedResponseWithoutTheKey(int errorStatus) {
        // Some providers echo (part of) the key in the error body; it must not reach the message.
        respond(errorStatus, "{\"error\":{\"message\":\"Your api key: " + KEY + " is invalid\"}}");

        LlmResponse response = completeAndCheckNoKeyLeaks(() -> client().complete(JSON_REQUEST));

        assertFalse(response.isOk());
        assertNull(response.getText());
        assertTrue(response.getError().startsWith("local: HTTP " + errorStatus), response.getError());
    }

    @Test
    void theErrorSaysWhatTheStatusMeans() {
        respond(401, "{}");
        assertEquals("local: HTTP 401, the API key was rejected", client().complete(JSON_REQUEST).getError());
        respond(429, "{}");
        assertEquals("local: HTTP 429, rate limit or quota reached", client().complete(JSON_REQUEST).getError());
        respond(500, "{}");
        assertEquals("local: HTTP 500, server error", client().complete(JSON_REQUEST).getError());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "not json at all",
            "{\"choices\":",
            "[]",
            "{}",
            "{\"choices\":[]}",
            "{\"choices\":[{\"message\":{\"content\":null}}]}",
            "{\"choices\":[{\"message\":{\"content\":\"   \"}}]}",
            "{\"choices\":[{\"message\":\"hello\"}]}",
            "{\"choices\":[{\"message\":{\"content\":{\"nested\":true}}}]}"})
    void anEmptyOrMalformedBodyIsAFailedResponse(String body) {
        respond(200, body);

        LlmResponse response = completeAndCheckNoKeyLeaks(() -> client().complete(JSON_REQUEST));

        assertFalse(response.isOk());
        assertEquals("local: the answer was empty or malformed", response.getError());
    }

    @Test
    void aTimeoutIsAFailedResponse() {
        hang = true;
        LocalClient impatient = new LocalClient(baseUrl(), KEY, Duration.ofMillis(200));

        LlmResponse response = completeAndCheckNoKeyLeaks(() -> impatient.complete(JSON_REQUEST));

        assertFalse(response.isOk());
        assertEquals("local: the request timed out", response.getError());
    }

    @Test
    void aRefusedConnectionIsAFailedResponse() {
        String closedUrl = baseUrl();
        server.stop(0);

        LlmResponse response = completeAndCheckNoKeyLeaks(
                () -> new LocalClient(closedUrl, KEY, Duration.ofSeconds(2)).complete(JSON_REQUEST));

        assertFalse(response.isOk());
        assertTrue(response.getError().startsWith("local: connection failed"), response.getError());
    }

    @Test
    void withoutAKeyItIsUnavailableAndSendsNothing() {
        for (String missing : new String[] {null, "", "   "}) {
            LocalClient keyless = new LocalClient(baseUrl(), missing, Duration.ofSeconds(5));

            assertFalse(keyless.isAvailable());
            LlmResponse response = keyless.complete(JSON_REQUEST);
            assertFalse(response.isOk());
            assertEquals("local: no API key is set", response.getError());
        }
        assertTrue(received.isEmpty());
        assertTrue(client().isAvailable());
    }

    @Test
    void aKeyThatCannotBeAHeaderIsAFailedResponseNotAnException() {
        String badKey = "sk-bad\nkey";
        LocalClient client = new LocalClient(baseUrl(), badKey, Duration.ofSeconds(5));

        LlmResponse response = client.complete(JSON_REQUEST);

        assertFalse(response.isOk());
        assertFalse(response.getError().contains(badKey), response.getError());
        assertTrue(received.isEmpty());
    }

    @Test
    void rejectsABadBaseUrlOrModel() {
        assertThrows(IllegalArgumentException.class, () -> new LocalClient("api.example.com/v1", KEY, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new LocalClient("ftp://example.com", KEY, Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class, () -> new LocalClient(null, KEY, Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class, () -> client().complete(null));
    }

    @Test
    void deepSeekAndGroqAddressTheirOwnEndpointAndModel() {
        DeepSeekClient deepSeek = new DeepSeekClient(KEY);
        GroqClient groq = new GroqClient(KEY);

        assertEquals("deepseek", deepSeek.providerName());
        assertEquals("groq", groq.providerName());
        assertTrue(deepSeek.isAvailable());
        assertFalse(new GroqClient(null).isAvailable());

        HttpRequest deepSeekRequest = deepSeek.toHttpRequest(JSON_REQUEST);
        assertEquals("https://api.deepseek.com/chat/completions", deepSeekRequest.uri().toString());
        assertEquals("POST", deepSeekRequest.method());
        assertEquals("Bearer " + KEY, deepSeekRequest.headers().firstValue("Authorization").orElseThrow());
        assertEquals("deepseek-flash", modelIn(deepSeekRequest));

        HttpRequest groqRequest = groq.toHttpRequest(JSON_REQUEST);
        assertEquals("https://api.groq.com/openai/v1/chat/completions", groqRequest.uri().toString());
        assertEquals("llama-3.3-70b-versatile", modelIn(groqRequest));
        // Only built, never sent: no network.
        assertFalse(deepSeekRequest.toString().contains(KEY));
    }

    /** The model field of a built request's body, read through its body publisher. */
    private static String modelIn(HttpRequest request) {
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
            }

            @Override
            public void onComplete() {
            }
        });
        return JsonParser.parseString(bytes.toString(StandardCharsets.UTF_8)).getAsJsonObject().get("model").getAsString();
    }
}
