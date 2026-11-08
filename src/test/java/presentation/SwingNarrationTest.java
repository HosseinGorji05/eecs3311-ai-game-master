package presentation;

import engine.character.CharacterClass;
import engine.core.FixedDiceRoller;
import engine.persistence.SaveManager;
import llm.ScriptedLLMClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** The story panel shows a typed turn's narration after the engine's text; a map click is not narrated. */
@Timeout(60)
class SwingNarrationTest {

    @TempDir
    Path saves;

    private SwingGameView view;

    @AfterEach
    void close() throws Exception {
        if (view != null) {
            SwingUtilities.invokeAndWait(() -> view.frame().dispose());
        }
    }

    private void edt(Runnable action) throws Exception {
        SwingUtilities.invokeAndWait(action);
        view.awaitIdle();
    }

    private String story() throws Exception {
        AtomicReference<String> text = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> text.set(view.storyArea().getText()));
        return text.get();
    }

    @Test
    void aTypedTurnIsNarratedInTheStoryPanelAndAMapClickIsNot() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        GameController controller = new GameController(new ScriptedLLMClient(List.of(
                "{\"backstory\":\"Ari grew up near the forge. Ari learned to fight. Ari seeks the stolen relic.\"}",
                "{\"actions\":[{\"type\":\"move\",\"target\":\"north\"}]}",
                "{\"narration\":\"You push open the door of the Tavern. Marta looks up from the bar.\"}")),
                new FixedDiceRoller(), new SaveManager(saves));
        AtomicReference<SwingGameView> created = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> created.set(new SwingGameView(controller)));
        view = created.get();
        controller.addView(view);
        edt(() -> {
            view.nameField().setText("Ari");
            view.classBox().setSelectedItem(CharacterClass.WARRIOR);
            view.createButton().doClick();
        });
        edt(() -> view.startButton().doClick());

        edt(() -> {
            view.inputField().setText("go north");
            view.sendButton().doClick();
        });

        String story = story();
        assertTrue(story.contains("> go north\nTavern\nA smoky room"), story);
        assertTrue(story.contains("Exits: south (Village Square)\nYou push open the door of the Tavern. Marta looks up"
                + " from the bar.\n"), story);
        assertFalse(story.contains("narration is pre-written"), story);

        edt(() -> {
            MapPanel map = view.mapPanel();
            map.setSize(420, 330);
            Rectangle box = map.nodeBounds(map.getModel().find("village-square"));
            map.dispatchEvent(new MouseEvent(map, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0,
                    (int) box.getCenterX(), (int) box.getCenterY(), 1, false, MouseEvent.BUTTON1));
        });

        String after = story();
        assertTrue(after.endsWith("Exits: north (Tavern), west (Blacksmith's Forge), east (Tool Shed, locked), south (Forest Edge)\n"),
                after.substring(Math.max(0, after.length() - 300)));
        assertFalse(after.contains("narration is pre-written"), "a map click is not narrated, so no notice either");
    }
}
