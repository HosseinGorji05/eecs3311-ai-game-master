package presentation;

import llm.DeepSeekClient;
import llm.GroqClient;
import llm.LLMClient;
import llm.ScriptedLLMClient;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Starts the game with an in-memory configuration and streams, so neither the real .env nor the
 * environment is read; a game is only played with the offline client (no network, no dice involved).
 */
class GameAppTest {

    private static final String DEEPSEEK_KEY = "sk-deepseek-test-key";
    private static final String GROQ_KEY = "gsk-groq-test-key";
    private static final EnvConfig NO_KEYS = new EnvConfig(Map.of(), Map.of());

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    private String play(String input, EnvConfig config, String... args) {
        GameApp.run(args, config, new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), out);
        return output();
    }

    private String output() {
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private LLMClient create(String provider, Map<String, String> fileValues, Map<String, String> environment) {
        return GameApp.createLlmClient(provider, new EnvConfig(fileValues, environment), out);
    }

    @Test
    void theOfflineProviderPlaysFromTheInputWithoutANotice() {
        String output = play("Ari\nmage\ngo north\ntake bread\nquit\n", NO_KEYS, "offline");

        assertTrue(output.startsWith("=== AI Game Master ==="), output);
        assertTrue(output.contains("(Basic-command mode: simple commands only.)"), output);
        assertTrue(output.contains("You take the bread."), output);
        assertTrue(output.endsWith("Goodbye.\n"), output);
    }

    @Test
    void withoutAKeyTheDefaultProviderSaysWhichKeyIsMissingAndPlaysOffline() {
        String output = play("Ari\nrogue\ngo north\nquit\n", NO_KEYS);

        assertTrue(output.startsWith("No API key for deepseek: set DEEPSEEK_API_KEY (or LLM_API_KEY) in .env "
                + "(copy .env.example) or in the environment; playing offline.\n=== AI Game Master ==="), output);
        assertTrue(output.contains("(Basic-command mode: simple commands only.)"), output);
        assertTrue(output.endsWith("Goodbye.\n"), output);
    }

    @Test
    void theProviderComesFromTheConfigurationWhenNoArgumentIsGiven() {
        String output = play("Ari\nrogue\nquit\n", new EnvConfig(Map.of("LLM_PROVIDER", "groq"), Map.of()));

        assertTrue(output.startsWith("No API key for groq: set GROQ_API_KEY (or LLM_API_KEY)"), output);
    }

    @Test
    void anUnknownProviderPlaysOffline() {
        String output = play("Ari\nrogue\nquit\n", NO_KEYS, "gpt");

        assertTrue(output.startsWith("Unknown LLM provider \"gpt\" (use deepseek, groq or offline); playing offline."), output);
        assertTrue(output.endsWith("Goodbye.\n"), output);
    }

    @Test
    void aDeepSeekKeyGivesTheRealClientAndIsNeverPrinted() {
        LLMClient client = create("deepseek", Map.of("DEEPSEEK_API_KEY", DEEPSEEK_KEY), Map.of());

        assertInstanceOf(DeepSeekClient.class, client);
        assertTrue(client.isAvailable());
        assertEquals("Using the deepseek LLM provider.\n", output());
        assertFalse(output().contains(DEEPSEEK_KEY));
    }

    @Test
    void aGroqKeyGivesTheGroqClientWhateverTheCase() {
        LLMClient client = create(" Groq ", Map.of(), Map.of("GROQ_API_KEY", GROQ_KEY));

        assertInstanceOf(GroqClient.class, client);
        assertTrue(client.isAvailable());
        assertFalse(output().contains(GROQ_KEY));
    }

    @Test
    void theGenericKeyIsUsedWhenTheProvidersOwnKeyIsMissing() {
        assertInstanceOf(DeepSeekClient.class, create("deepseek", Map.of("LLM_API_KEY", "sk-generic"), Map.of()));
        assertInstanceOf(GroqClient.class, create("groq", Map.of("DEEPSEEK_API_KEY", DEEPSEEK_KEY), Map.of("LLM_API_KEY", "sk-generic")));
        assertFalse(output().contains("sk-generic"));
    }

    @Test
    void anotherProvidersKeyDoesNotCount() {
        LLMClient client = create("groq", Map.of("DEEPSEEK_API_KEY", DEEPSEEK_KEY), Map.of());

        assertInstanceOf(ScriptedLLMClient.class, client);
        assertFalse(client.isAvailable());
        assertTrue(output().startsWith("No API key for groq: set GROQ_API_KEY"), output());
        assertFalse(output().contains(DEEPSEEK_KEY));
    }

    @Test
    void offlineNeedsNoKeyAndPrintsNothing() {
        LLMClient client = create("offline", Map.of("DEEPSEEK_API_KEY", DEEPSEEK_KEY), Map.of());

        assertInstanceOf(ScriptedLLMClient.class, client);
        assertEquals("", output());
    }
}
