package agent;

import engine.core.GameState;
import llm.LLMClient;
import llm.LlmRequest;
import llm.LlmResponse;
import llm.ScriptedLLMClient;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /** Always fails with the given error, as an HTTP client does. */
    private static LLMClient failingWith(String error) {
        return new LLMClient() {
            @Override
            public LlmResponse complete(LlmRequest request) {
                return new LlmResponse(null, false, error);
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String providerName() {
                return "failing";
            }
        };
    }

    @Test
    void theFallbackReasonNamesTheLastFailureAsACategory() {
        NumberTask unavailable = new NumberTask(new CountingClient(false));
        unavailable.run("x");
        assertEquals("LLM unavailable", unavailable.getFallbackReason());

        NumberTask rejected = new NumberTask(failingWith("HTTP 401 (API key rejected)"));
        rejected.run("x");
        assertEquals("LLM unavailable: HTTP 401 (API key rejected)", rejected.getFallbackReason());

        NumberTask timedOut = new NumberTask(failingWith("timeout"));
        timedOut.run("x");
        assertEquals("LLM unavailable: timeout", timedOut.getFallbackReason());

        NumberTask invalid = new NumberTask(new CountingClient(false, "-1", "seven"));
        invalid.run("x");
        assertEquals("invalid output", invalid.getFallbackReason());

        NumberTask throwing = new NumberTask(new CountingClient(true));
        throwing.run("x");
        assertEquals("LLM unavailable: client error", throwing.getFallbackReason());
    }

    @Test
    void thereIsNoFallbackReasonWhenTheModelAnswered() {
        NumberTask task = new NumberTask(new CountingClient(false, "-1", "-2", "6"));
        task.run("first");
        task.run("second");

        assertFalse(task.isFallbackUsed());
        assertNull(task.getFallbackReason());
    }

    @Test
    void theDebugOutputShowsEachFailedAttemptsReasonAndReplyButNotTheRequest() {
        NumberTask task = new NumberTask(new CountingClient(false, "-5", "seven"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        task.setDebugOutput(new PrintStream(bytes, true, StandardCharsets.UTF_8));

        task.run("number please");

        String debug = bytes.toString(StandardCharsets.UTF_8);
        assertEquals("""
                [LLM_DEBUG] NumberTask attempt 1/2 failed: invalid output - the answer failed validation
                [LLM_DEBUG] reply: -5
                [LLM_DEBUG] NumberTask attempt 2/2 failed: invalid output - the reply could not be parsed
                [LLM_DEBUG] reply: seven
                """, debug);
        assertFalse(debug.contains("Answer with a positive number."), "the prompt is never printed");
        assertFalse(debug.contains("number please"), "the input is never printed");
    }

    @Test
    void withoutADebugOutputNothingIsPrinted() {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(bytes, true, StandardCharsets.UTF_8));
            NumberTask task = new NumberTask(new CountingClient(false, "-5", "seven"));
            task.run("x");
            task.setDebugOutput(new PrintStream(new ByteArrayOutputStream()));
            task.setDebugOutput(null);
            task.run("x");
        } finally {
            System.setErr(originalErr);
        }
        assertEquals("", bytes.toString(StandardCharsets.UTF_8));
    }
}
