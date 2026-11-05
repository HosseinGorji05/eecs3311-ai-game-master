package agent;

import engine.core.GameState;
import llm.LLMClient;
import llm.LlmRequest;
import llm.LlmResponse;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A rejected key is not asked again; invalid output and timeouts still get their second attempt. */
class LlmTaskRetryTest {

    /** Answers each call with the next scripted response and counts the calls. */
    private static final class Client implements LLMClient {
        private final List<LlmResponse> responses;
        private final boolean available;
        int calls;
        int availabilityChecks;

        Client(boolean available, LlmResponse... responses) {
            this.available = available;
            this.responses = new ArrayList<>(List.of(responses));
        }

        @Override
        public LlmResponse complete(LlmRequest request) {
            calls++;
            return responses.size() > 1 ? responses.remove(0) : responses.get(0);
        }

        @Override
        public boolean isAvailable() {
            availabilityChecks++;
            return available;
        }

        @Override
        public String providerName() {
            return "test";
        }
    }

    /** Reads a number; falls back to -1. */
    private static final class NumberTask extends LlmTask<String, Integer> {
        NumberTask(LLMClient llm) {
            super(llm, new PromptBuilder(), new ResponseParser(), new GameState());
        }

        @Override
        protected LlmRequest buildPrompt(String input) {
            return new LlmRequest("system", input, false, 10);
        }

        @Override
        protected Integer parse(LlmResponse response) {
            try {
                return Integer.valueOf(response.getText().trim());
            } catch (NumberFormatException notANumber) {
                return null;
            }
        }

        @Override
        protected boolean isValid(Integer output, String input) {
            return true;
        }

        @Override
        protected Integer fallback(String input) {
            return -1;
        }
    }

    private static LlmResponse failure(String error) {
        return new LlmResponse(null, false, error);
    }

    @Test
    void http401IsNotRetriedAndFallsBackAtOnceWithTheReason() {
        Client client = new Client(true, failure("HTTP 401 (API key rejected)"), new LlmResponse("7", true, null));
        NumberTask task = new NumberTask(client);
        ByteArrayOutputStream debug = new ByteArrayOutputStream();
        task.setDebugOutput(new PrintStream(debug, true, StandardCharsets.UTF_8));

        assertEquals(-1, task.run("x"));

        assertEquals(1, client.calls);
        assertEquals("LLM unavailable: HTTP 401 (API key rejected)", task.getFallbackReason());
        String text = debug.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("attempt 1/2 failed: LLM unavailable: HTTP 401 (API key rejected) - HTTP 401 (API key rejected) (not retried)"), text);
        assertTrue(!text.contains("attempt 2/2"), text);
    }

    @Test
    void http403IsNotRetriedEither() {
        Client client = new Client(true, failure("HTTP 403 (API key rejected)"));
        NumberTask task = new NumberTask(client);

        task.run("x");

        assertEquals(1, client.calls);
        assertEquals("LLM unavailable: HTTP 403 (API key rejected)", task.getFallbackReason());
    }

    @Test
    void aClientThatIsNotAvailableIsAskedOnce() {
        Client client = new Client(false, new LlmResponse("7", true, null));
        NumberTask task = new NumberTask(client);

        assertEquals(-1, task.run("x"));

        assertEquals(0, client.calls);
        assertEquals(1, client.availabilityChecks);
        assertEquals("LLM unavailable", task.getFallbackReason());
    }

    @Test
    void aTimeoutIsRetriedAndTheSecondAnswerIsUsed() {
        Client client = new Client(true, failure("timeout"), new LlmResponse("7", true, null));
        NumberTask task = new NumberTask(client);

        assertEquals(7, task.run("x"));
        assertEquals(2, client.calls);
    }

    @Test
    void invalidOutputIsRetried() {
        Client client = new Client(true, new LlmResponse("seven", true, null), new LlmResponse("7", true, null));
        NumberTask task = new NumberTask(client);

        assertEquals(7, task.run("x"));
        assertEquals(2, client.calls);
    }

    @Test
    void otherErrorsAreStillRetried() {
        Client client = new Client(true, failure("HTTP 429 (rate limit or quota)"), failure("HTTP 500 (server error)"));
        NumberTask task = new NumberTask(client);

        assertEquals(-1, task.run("x"));
        assertEquals(2, client.calls);
        assertEquals("LLM unavailable: HTTP 500 (server error)", task.getFallbackReason());
    }

    @Test
    void onlyTheRejectedKeyCategoriesArePermanent() {
        assertTrue(LlmTask.isPermanent("HTTP 401 (API key rejected)"));
        assertTrue(LlmTask.isPermanent("HTTP 403 (API key rejected)"));
        assertTrue(!LlmTask.isPermanent("HTTP 404 (model x not found; set GROQ_MODEL)"));
        assertTrue(!LlmTask.isPermanent("timeout"));
        assertTrue(!LlmTask.isPermanent(null));
    }
}
