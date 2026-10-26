package agent;

import engine.core.GameState;
import llm.LLMClient;
import llm.LlmRequest;
import llm.LlmResponse;
import llm.ScriptedLLMClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The template method of SD00 with a tiny task: the model must answer a positive number. */
class LlmTaskTest {

    /** Parses the answer as a number; valid if positive; the fallback is -1. */
    private static final class NumberTask extends LlmTask<String, Integer> {
        int fallbackCalls;

        NumberTask(LLMClient llm) {
            super(llm, new PromptBuilder(), new ResponseParser(), new GameState());
        }

        @Override
        protected LlmRequest buildPrompt(String input) {
            return new LlmRequest("Answer with a positive number.", input, false, 10);
        }

        @Override
        protected Integer parse(LlmResponse response) {
            try {
                return Integer.valueOf(response.getText().trim());
            } catch (NumberFormatException malformed) {
                return null;
            }
        }

        @Override
        protected boolean isValid(Integer output, String input) {
            return output > 0;
        }

        @Override
        protected Integer fallback(String input) {
            fallbackCalls++;
            return -1;
        }
    }

    /** Counts the calls to a scripted client, or throws on every call. */
    private static final class CountingClient implements LLMClient {
        private final ScriptedLLMClient scripted;
        private final boolean throwing;
        int completeCalls;

        CountingClient(boolean throwing, String... responses) {
            this.scripted = new ScriptedLLMClient(List.of(responses));
            this.throwing = throwing;
        }

        @Override
        public LlmResponse complete(LlmRequest request) {
            completeCalls++;
            if (throwing) {
                throw new IllegalStateException("connection reset");
            }
            return scripted.complete(request);
        }

        @Override
        public boolean isAvailable() {
            return throwing || scripted.isAvailable();
        }

        @Override
        public String providerName() {
            return "counting";
        }
    }

    @Test
    void aValidFirstAnswerIsReturnedWithoutRetryOrFallback() {
        CountingClient client = new CountingClient(false, "7", "8");
        NumberTask task = new NumberTask(client);

        assertEquals(7, task.run("number please"));
        assertEquals(1, client.completeCalls);
        assertFalse(task.isFallbackUsed());
        assertEquals(0, task.fallbackCalls);
    }

    @Test
    void anInvalidAnswerIsRetriedOnce() {
        CountingClient client = new CountingClient(false, "-5", "9");
        NumberTask task = new NumberTask(client);

        assertEquals(9, task.run("number please"));
        assertEquals(2, client.completeCalls);
        assertFalse(task.isFallbackUsed());
    }

    @Test
    void aMalformedAnswerIsRetriedOnce() {
        CountingClient client = new CountingClient(false, "seven", "3");
        NumberTask task = new NumberTask(client);

        assertEquals(3, task.run("number please"));
        assertFalse(task.isFallbackUsed());
    }

    @Test
    void twoUnusableAnswersLeadToTheFallback() {
        CountingClient client = new CountingClient(false, "-1", "not a number", "4");
        NumberTask task = new NumberTask(client);

        assertEquals(-1, task.run("number please"));
        assertTrue(task.isFallbackUsed());
        assertEquals(2, client.completeCalls, "MAX_ATTEMPTS is 2");
        assertEquals(1, task.fallbackCalls);
    }

    @Test
    void aClientThatThrowsCountsAsAFailedAttemptAndNeverCrashesTheTask() {
        CountingClient client = new CountingClient(true);
        NumberTask task = new NumberTask(client);

        assertEquals(-1, task.run("number please"));
        assertTrue(task.isFallbackUsed());
        assertEquals(2, client.completeCalls);
    }

    @Test
    void anUnavailableClientIsNotAskedAndTheFallbackIsUsed() {
        CountingClient client = new CountingClient(false);
        NumberTask task = new NumberTask(client);

        assertEquals(-1, task.run("number please"));
        assertTrue(task.isFallbackUsed());
        assertEquals(0, client.completeCalls);
    }

    @Test
    void fallbackUsedIsResetOnEveryRun() {
        CountingClient client = new CountingClient(false, "-1", "-2", "6");
        NumberTask task = new NumberTask(client);
        task.run("first");
        assertTrue(task.isFallbackUsed());

        assertEquals(6, task.run("second"));

        assertFalse(task.isFallbackUsed());
    }
}
