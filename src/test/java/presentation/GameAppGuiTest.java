package presentation;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Choosing the GUI in GameApp. The launcher is a stand-in, so no window opens; the configuration is in
 * memory (no .env, no key, no network).
 */
class GameAppGuiTest {

    private static final EnvConfig NO_KEYS = new EnvConfig(Map.of(), Map.of());

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
    private final PrintStream err = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    private final List<GameController> launched = new ArrayList<>();

    private String run(boolean headless, String input, String... args) {
        GameApp.run(args, NO_KEYS, new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), out, err,
                headless, launched::add);
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void guiStartsTheWindowWithAControllerInsteadOfTheCli() {
        String output = run(false, "", "gui", "offline");

        assertEquals(1, launched.size());
        assertNotNull(launched.get(0).getState());
        assertFalse(output.contains("=== AI Game Master ==="), "the CLI did not run: " + output);
    }

    @Test
    void theProviderAfterGuiIsUsedAndWithoutOneTheConfigurationDecides() {
        String output = run(false, "", "GUI");

        assertEquals(1, launched.size());
        assertTrue(output.contains("No API key for deepseek"), output);
    }

    @Test
    void withoutADisplayTheGuiIsNotStartedAndTheCliIsSuggested() {
        String output = run(true, "", "gui", "offline");

        assertTrue(launched.isEmpty());
        assertEquals(GameApp.NO_DISPLAY + "\n", output);
        assertTrue(output.contains("./mvnw -q compile exec:java"), output);
    }

    @Test
    void withoutGuiTheCliPlaysAsBeforeEvenWithoutADisplay() {
        String output = run(true, "Ari\nmage\ngo north\nquit\n", "offline");

        assertTrue(launched.isEmpty());
        assertTrue(output.startsWith("=== AI Game Master ==="), output);
        assertTrue(output.endsWith("Goodbye.\n"), output);
    }

    @Test
    void theStreamOnlyRunNeverOpensAWindow() {
        GameApp.run(new String[] {"gui", "offline"}, NO_KEYS, new ByteArrayInputStream(new byte[0]), out, err);

        assertEquals(GameApp.NO_DISPLAY + "\n", bytes.toString(StandardCharsets.UTF_8));
    }
}
