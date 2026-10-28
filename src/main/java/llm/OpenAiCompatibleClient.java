package llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * An {@link LLMClient} for providers with an OpenAI-compatible chat API: it sends
 * {@code POST {baseUrl}/chat/completions} through the JDK's {@link HttpClient} and reads the first
 * choice's message content (SD00).
 *
 * <p>Pattern role: <b>Adapter</b> (Target {@link LLMClient}, Adaptee {@link HttpClient}). Every
 * failure (no API key, connection error, timeout, an HTTP error status, an empty or malformed body)
 * becomes a failed {@link LlmResponse} instead of an exception, so the calling task retries and then
 * falls back. Its error is a short category such as {@code "HTTP 401 (API key rejected)"} or
 * {@code "HTTP 404 (model <model> not found; set GROQ_MODEL)"}, never a response body. The API key
 * is only ever put in the Authorization header; it is removed from every error message.
 *
 * <p>If the provider answers a JSON-mode request with HTTP 400, the request is sent once more
 * without {@code response_format} (not every model supports it); if that works, later requests
 * leave it out, and the prompt alone asks for JSON.
 */
public abstract class OpenAiCompatibleClient implements LLMClient {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    /** Low, so JSON answers stay close to the requested format; {@link LlmRequest} has no temperature. */
    static final double TEMPERATURE = 0.2;

    private static final Gson GSON = new Gson();

    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final HttpClient http;
    private final Duration requestTimeout;
    private volatile boolean responseFormatSupported = true;

    /**
     * @param apiKey the provider's key; {@code null} or blank makes the client unavailable
     * @throws IllegalArgumentException if {@code baseUrl} is not an absolute http(s) URL or the
     *                                  model is blank
     */
    protected OpenAiCompatibleClient(String baseUrl, String model, String apiKey) {
        this(baseUrl, model, apiKey, REQUEST_TIMEOUT);
    }

    /** Creates a client with its own request timeout, so tests need not wait the full one. */
    OpenAiCompatibleClient(String baseUrl, String model, String apiKey, Duration requestTimeout) {
        this.baseUrl = stripTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
        URI uri = URI.create(this.baseUrl);
        if (!uri.isAbsolute() || !uri.getScheme().matches("https?")) {
            throw new IllegalArgumentException("baseUrl must be an absolute http(s) URL: " + baseUrl);
        }
        if (Objects.requireNonNull(model, "model").isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        this.model = model;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    /**
     * Asks the model (a second time only without {@code response_format}, see above); never throws,
     * and answers with an error response on any failure.
     */
    @Override
    public LlmResponse complete(LlmRequest request) {
        Objects.requireNonNull(request, "request");
        if (!isAvailable()) {
            return failure("no API key");
        }
        try {
            HttpResponse<String> response = send(request);
            if (response.statusCode() == 400 && request.isJsonMode() && responseFormatSupported) {
                responseFormatSupported = false;
                response = send(request);
                // Keep leaving it out only if that was the problem.
                responseFormatSupported = response.statusCode() < 200 || response.statusCode() >= 300;
            }
            return toLlmResponse(response);
        } catch (HttpTimeoutException timeout) {
            return failure("timeout");
        } catch (IOException connection) {
            return failure("connection failed (" + connection.getClass().getSimpleName() + ")");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failure("interrupted");
        } catch (RuntimeException unexpected) {
            return failure("client error (" + unexpected.getClass().getSimpleName() + ")");
        }
    }

    /** Available when an API key is set; whether the provider answers shows only in {@link #complete}. */
    @Override
    public boolean isAvailable() {
        return !apiKey.isEmpty();
    }

    @Override
    public abstract String providerName();

    /** The model ID sent with every request. */
    public String getModel() {
        return model;
    }

    /**
     * The configuration setting that chooses this provider's model, named in the HTTP 404 error;
     * {@code null} (the default) if there is none.
     */
    protected String modelSetting() {
        return null;
    }

    /**
     * The chat-completions request: the system and user prompts as two messages, the model, the
     * answer length limit, the temperature and, for JSON mode, {@code response_format json_object}
     * (unless the provider rejected it before).
     */
    protected HttpRequest toHttpRequest(LlmRequest request) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        JsonArray messages = new JsonArray();
        messages.add(message("system", request.getSystemPrompt()));
        messages.add(message("user", request.getUserPrompt()));
        body.add("messages", messages);
        body.addProperty("max_tokens", request.getMaxTokens());
        body.addProperty("temperature", TEMPERATURE);
        body.addProperty("stream", false);
        if (request.isJsonMode() && responseFormatSupported) {
            JsonObject format = new JsonObject();
            format.addProperty("type", "json_object");
            body.add("response_format", format);
        }
        return HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                .build();
    }

    /**
     * The first choice's message content for a 2xx status; otherwise an error naming the status.
     * The body of an error response is not copied into the message, since providers may echo
     * part of the key there.
     */
    protected LlmResponse toLlmResponse(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            return failure("HTTP " + status + " (API key rejected)");
        }
        if (status == 404) {
            // A retired or unknown model; the URLs are fixed, so the path is not the cause.
            String setting = modelSetting();
            return failure("HTTP 404 (model " + model + " not found" + (setting == null ? "" : "; set " + setting) + ")");
        }
        if (status == 429) {
            return failure("HTTP 429 (rate limit or quota)");
        }
        if (status >= 500) {
            return failure("HTTP " + status + " (server error)");
        }
        if (status < 200 || status >= 300) {
            return failure("HTTP " + status);
        }
        String content = content(response.body());
        return content == null || content.isBlank()
                ? failure("empty or malformed reply")
                : new LlmResponse(content, true, null);
    }

    /**
     * {@code choices[0].message.content}, or {@code null} if the body does not have it. A reasoning
     * model's separate {@code message.reasoning} field is ignored.
     */
    private static String content(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonObject()) {
                return null;
            }
            JsonElement choices = root.getAsJsonObject().get("choices");
            if (choices == null || !choices.isJsonArray() || choices.getAsJsonArray().isEmpty()) {
                return null;
            }
            JsonElement choice = choices.getAsJsonArray().get(0);
            JsonElement message = choice.isJsonObject() ? choice.getAsJsonObject().get("message") : null;
            JsonElement text = message != null && message.isJsonObject() ? message.getAsJsonObject().get("content") : null;
            return text != null && text.isJsonPrimitive() ? text.getAsString() : null;
        } catch (JsonParseException | IllegalStateException | UnsupportedOperationException malformed) {
            return null;
        }
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private HttpResponse<String> send(LlmRequest request) throws IOException, InterruptedException {
        return http.send(toHttpRequest(request), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private LlmResponse failure(String category) {
        return new LlmResponse(null, false, redact(category));
    }

    /** The text with every occurrence of the API key replaced. */
    private String redact(String text) {
        return apiKey.isEmpty() ? text : text.replace(apiKey, "[redacted]");
    }

    private static String stripTrailingSlash(String url) {
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
