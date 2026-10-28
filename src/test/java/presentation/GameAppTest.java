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
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

    private String play(String input, EnvConfig config, String... args) {
        GameApp.run(args, config, new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), out, err);
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
        assertTrue(output.contains("(Basic-command mode: simple commands only. Reason: LLM unavailable.)"), output);
        assertTrue(output.contains("You take the bread."), output);
        assertTrue(output.endsWith("Goodbye.\n"), output);
    }

    @Test
    void withoutAKeyTheDefaultProviderSaysWhichKeyIsMissingAndPlaysOffline() {
        String output = play("Ari\nrogue\ngo north\nquit\n", NO_KEYS);

        assertTrue(output.startsWith("No API key for deepseek: set DEEPSEEK_API_KEY (or LLM_API_KEY) in .env "
                + "(copy .env.example) or in the environment; playing offline.\n=== AI Game Master ==="), output);
        assertTrue(output.contains("(Basic-command mode: simple commands only. Reason: LLM unavailable.)"), output);
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
        assertEquals("Using the deepseek LLM provider (deepseek-flash).\n", output());
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
    void aModelSettingOverridesTheDefaultModelAndTheEnvironmentWins() {
        GroqClient groq = (GroqClient) create("groq",
                Map.of("GROQ_API_KEY", GROQ_KEY, "GROQ_MODEL", "qwen/qwen3.8-27b"),
                Map.of("GROQ_MODEL", "openai/gpt-oss-120b"));
        DeepSeekClient deepSeek = (DeepSeekClient) create("deepseek",
                Map.of("DEEPSEEK_API_KEY", DEEPSEEK_KEY, "DEEPSEEK_MODEL", "deepseek-other"), Map.of());

        assertEquals("openai/gpt-oss-120b", groq.getModel());
        assertEquals("deepseek-other", deepSeek.getModel());
        assertTrue(output().contains("Using the groq LLM provider (openai/gpt-oss-120b)."), output());
    }

    @Test
    void aBlankModelSettingKeepsTheDefaultModel() {
        GroqClient groq = (GroqClient) create("groq", Map.of("GROQ_API_KEY", GROQ_KEY, "GROQ_MODEL", ""),
                Map.of("GROQ_MODEL", "  "));

        assertEquals(GroqClient.DEFAULT_MODEL, groq.getModel());
        assertEquals("openai/gpt-oss-20b", groq.getModel());
        assertEquals(GroqClient.MODEL_SETTING, EnvConfig.GROQ_MODEL);
        assertEquals(DeepSeekClient.MODEL_SETTING, EnvConfig.DEEPSEEK_MODEL);
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
    void theMissingKeyNoticeSaysWhenTheOtherProvidersKeyIsSet() {
        LLMClient client = create("deepseek", Map.of("LLM_PROVIDER", "deepseek", "GROQ_API_KEY", GROQ_KEY), Map.of());

        assertInstanceOf(ScriptedLLMClient.class, client);
        assertEquals("No API key for deepseek: set DEEPSEEK_API_KEY (or LLM_API_KEY) in .env (copy .env.example) "
                + "or in the environment; playing offline. GROQ_API_KEY is set: use LLM_PROVIDER=groq to play with groq.\n",
                output());
        assertFalse(output().contains(GROQ_KEY));
    }

    @Test
    void aGroqKeyGivenToDeepSeekIsWarnedAboutWithoutPrintingIt() {
        String groqStyleKey = "gsk_live_looking_test_value";
        LLMClient client = create("deepseek", Map.of("LLM_API_KEY", groqStyleKey), Map.of());

        assertInstanceOf(DeepSeekClient.class, client);
        assertTrue(output().startsWith("Warning: the key for deepseek looks like a groq key (it starts with \"gsk_\"); "
                + "set LLM_PROVIDER=groq if it is one.\nUsing the deepseek LLM provider (deepseek-flash)."), output());
        assertFalse(output().contains(groqStyleKey));
    }

    @Test
    void aDeepSeekKeyGivenToGroqIsWarnedAbout() {
        create("groq", Map.of("GROQ_API_KEY", "sk-deepseek-looking-test-value"), Map.of());

        assertTrue(output().startsWith("Warning: the key for groq looks like a deepseek key"), output());
        assertFalse(output().contains("sk-deepseek-looking-test-value"));
    }

    @Test
    void llmDebugInTheEnvironmentDescribesFailedAttemptsOnStandardError() {
        String output = play("Ari\nrogue\ngo north\nquit\n", new EnvConfig(Map.of(), Map.of("LLM_DEBUG", "true")), "offline");

        assertTrue(output.startsWith("LLM debug output is on (standard error).\n=== AI Game Master ==="), output);
        String debug = errBytes.toString(StandardCharsets.UTF_8);
        assertTrue(debug.contains("[LLM_DEBUG] ActionInterpreter attempt 1/2 failed: LLM unavailable"), debug);
        assertTrue(debug.contains("[LLM_DEBUG] reply: (none)"), debug);
        assertFalse(output.contains("[LLM_DEBUG]"), "debug output goes to standard error only");
    }

    @Test
    void withoutLlmDebugNothingGoesToStandardError() {
        play("Ari\nrogue\ngo north\nquit\n", new EnvConfig(Map.of(), Map.of("LLM_DEBUG", "no")), "offline");

        assertEquals("", errBytes.toString(StandardCharsets.UTF_8));
        assertFalse(output().contains("LLM debug"));
    }

    @Test
    void offlineNeedsNoKeyAndPrintsNothing() {
        LLMClient client = create("offline", Map.of("DEEPSEEK_API_KEY", DEEPSEEK_KEY), Map.of());

        assertInstanceOf(ScriptedLLMClient.class, client);
        assertEquals("", output());
    }
}
