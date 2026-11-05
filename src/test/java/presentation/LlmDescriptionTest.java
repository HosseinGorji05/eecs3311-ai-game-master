package presentation;

import engine.character.CharacterClass;
import engine.character.Stats;
import llm.DeepSeekClient;
import llm.GroqClient;
import llm.ScriptedLLMClient;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The window title and help name the provider and model, or offline, and never a key. The real clients are
 * only constructed (with made-up keys), never asked anything.
 */
class LlmDescriptionTest {

    private static final String FAKE_GROQ_KEY = "gsk_made_up_test_key_123";
    private static final String FAKE_DEEPSEEK_KEY = "sk-made-up-test-key-456";

    @Test
    void theControllerNamesTheProviderAndModelOrOffline() {
        assertEquals("groq (openai/gpt-oss-20b)",
                new GameController(new GroqClient(FAKE_GROQ_KEY, "openai/gpt-oss-20b")).getLlmDescription());
        assertEquals("deepseek (deepseek-flash)",
                new GameController(new DeepSeekClient(FAKE_DEEPSEEK_KEY, "deepseek-flash")).getLlmDescription());
        assertEquals("offline", new GameController(new ScriptedLLMClient(List.of())).getLlmDescription());
    }

    @Test
    void theTitleAndHelpShowTheDescriptionAndNoKey() {
        String description = new GameController(new GroqClient(FAKE_GROQ_KEY, "openai/gpt-oss-20b")).getLlmDescription();

        String title = GuiText.windowTitle(description);
        String help = GuiText.help(description);

        assertEquals("AI Game Master - groq (openai/gpt-oss-20b)", title);
        assertTrue(help.startsWith(GuiText.HELP), help);
        assertTrue(help.endsWith("\nLanguage model: groq (openai/gpt-oss-20b)."), help);
        for (String text : List.of(title, help)) {
            assertFalse(text.contains(FAKE_GROQ_KEY) || text.contains("gsk_") || text.contains("made_up"), text);
        }
    }

    @Test
    void offlineTheHelpSaysBasicCommands() {
        assertEquals("AI Game Master - offline", GuiText.windowTitle("offline"));
        assertTrue(GuiText.help("offline").endsWith(
                "\nLanguage model: offline (basic commands only, e.g. \"go north\", \"take bread\")."));
    }

    @Test
    void theWindowTitleAndTheFirstHelpUseIt() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        GameController controller = new GameController(new GroqClient(FAKE_GROQ_KEY, "openai/gpt-oss-20b"));
        AtomicReference<SwingGameView> view = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> view.set(new SwingGameView(controller)));
        try {
            controller.addView(view.get());
            controller.newGame("Ari", CharacterClass.MAGE, new Stats(2, 3, 5));
            controller.startGame();
            view.get().awaitIdle();

            AtomicReference<String> title = new AtomicReference<>();
            AtomicReference<String> story = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                title.set(view.get().frame().getTitle());
                story.set(view.get().storyArea().getText());
            });
            assertEquals("AI Game Master - groq (openai/gpt-oss-20b)", title.get());
            assertTrue(story.get().contains("Language model: groq (openai/gpt-oss-20b)."), story.get());
            assertFalse(story.get().contains(FAKE_GROQ_KEY), story.get());
        } finally {
            SwingUtilities.invokeAndWait(() -> view.get().frame().dispose());
        }
    }
}
