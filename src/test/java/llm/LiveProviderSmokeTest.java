package llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Optional checks against the real providers. Skipped unless both {@code LLM_LIVE_TEST=true} and the
 * provider's key are set in the environment, so a plain {@code ./mvnw -q test} never uses the network,
 * even when a key is exported for playing. Run with
 * {@code LLM_LIVE_TEST=true DEEPSEEK_API_KEY=... ./mvnw -q test -Dtest=LiveProviderSmokeTest}.
 */
class LiveProviderSmokeTest {

    private static final LlmRequest REQUEST = new LlmRequest(
            "Answer with JSON only.", "Reply with {\"ok\":true}.", true, 20);

    @Test
    @EnabledIfEnvironmentVariable(named = "LLM_LIVE_TEST", matches = "true")
    @EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".*\\S.*")
    void deepSeekAnswers() {
        check(new DeepSeekClient(System.getenv("DEEPSEEK_API_KEY")), System.getenv("DEEPSEEK_API_KEY"));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "LLM_LIVE_TEST", matches = "true")
    @EnabledIfEnvironmentVariable(named = "GROQ_API_KEY", matches = ".*\\S.*")
    void groqAnswers() {
        check(new GroqClient(System.getenv("GROQ_API_KEY")), System.getenv("GROQ_API_KEY"));
    }

    /** The assertion messages show the error, which never contains the key; the key itself is never printed. */
    private static void check(LLMClient client, String key) {
        LlmResponse response = client.complete(REQUEST);
        String error = String.valueOf(response.getError());
        assertFalse(error.contains(key.trim()), "error message contains the key");
        assertTrue(response.isOk(), error);
        assertFalse(response.getText().isBlank());
    }
}
