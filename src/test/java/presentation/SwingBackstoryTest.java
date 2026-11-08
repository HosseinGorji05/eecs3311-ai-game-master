package presentation;

import engine.character.CharacterClass;
import engine.character.Player;
import engine.character.Stats;
import engine.core.FixedDiceRoller;
import engine.persistence.SaveManager;
import llm.ScriptedLLMClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The New Game screen's Generate Backstory, Regenerate and Start (F01, SD01) through the real controller with
 * scripted replies. The window is never shown. Skipped, not failed, without a display.
 */
@Timeout(60)
class SwingBackstoryTest {

    private static final String FIRST = "Ari grew up in a river town. Ari learned to pick locks early. Ari left after"
            + " a bad debt.";
    private static final String SECOND = "Ari was raised by smugglers. Ari learned to keep quiet. Ari came inland.";
    private static final String THIRD = "Ari was a temple orphan. Ari ran away at twelve. Ari never looked back.";

    /** Records the creation calls and whether each ran on the event thread. */
    private static final class RecordingController extends GameController {
        final List<String> calls = new CopyOnWriteArrayList<>();

        RecordingController(Path saves, String... replies) {
            super(new ScriptedLLMClient(List.of(replies)), new FixedDiceRoller(), new SaveManager(saves));
        }

        private void record(String call) {
            calls.add(call + (SwingUtilities.isEventDispatchThread() ? " (event thread!)" : ""));
        }

        @Override
        public Player newGame(String name, CharacterClass cls, Stats stats) {
            record("newGame");
            return super.newGame(name, cls, stats);
        }

        @Override
        public String regenerateBackstory() {
            record("regenerateBackstory");
            return super.regenerateBackstory();
        }

        @Override
        public void startGame() {
            record("startGame");
            super.startGame();
        }
    }

    @TempDir
    Path saves;

    private RecordingController controller;
    private SwingGameView view;

    private void open(String... replies) throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        controller = new RecordingController(saves, replies);
        view = onEdt(() -> new SwingGameView(controller));
        controller.addView(view);
        edt(() -> {
            view.nameField().setText("Ari");
            view.classBox().setSelectedItem(CharacterClass.ROGUE);
            view.statSliders().get(0).setValue(2);
            view.statSliders().get(1).setValue(6);
            view.statSliders().get(2).setValue(2);
        });
    }

    @AfterEach
    void close() throws Exception {
        if (view != null) {
            SwingUtilities.invokeAndWait(() -> view.frame().dispose());
        }
    }

    private static <T> T onEdt(Callable<T> task) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(task.call());
            } catch (Exception e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    private void edt(Runnable action) throws Exception {
        SwingUtilities.invokeAndWait(action);
        view.awaitIdle();
    }

    @Test
    void generateShowsTheBackstoryWithRegenerateAndStartAndStartBeginsWithIt() throws Exception {
        open(FIRST, SECOND, THIRD);
        assertFalse(onEdt(() -> view.backstoryPanel().isVisible()));
        assertFalse(onEdt(() -> view.startButton().isEnabled()));

        edt(() -> view.createButton().doClick());
        assertTrue(onEdt(() -> view.backstoryPanel().isVisible()));
        assertEquals(FIRST, onEdt(() -> view.backstoryArea().getText()));
        assertTrue(onEdt(() -> view.regenerateButton().isEnabled()));
        assertTrue(onEdt(() -> view.startButton().isEnabled()));

        edt(() -> view.regenerateButton().doClick());
        assertEquals(SECOND, onEdt(() -> view.backstoryArea().getText()));
        edt(() -> view.regenerateButton().doClick());
        assertEquals(THIRD, onEdt(() -> view.backstoryArea().getText()));

        edt(() -> view.startButton().doClick());

        assertEquals(List.of("newGame", "regenerateBackstory", "regenerateBackstory", "regenerateBackstory", "startGame"),
                controller.calls, "every call off the event thread");
        assertEquals(SwingGameView.GAME_CARD, onEdt(view::currentCard));
        assertEquals(THIRD, controller.getState().getPlayer().getBackstory());
        String story = onEdt(() -> view.storyArea().getText());
        assertTrue(story.contains("Ari's story:\n" + THIRD + "\n\nVillage Square"), story);
        assertEquals(1, story.split("Ari's story:", -1).length - 1, "shown once");
    }

    @Test
    void editingTheFormAfterGeneratingDiscardsTheBackstoryUntilItIsGeneratedAgain() throws Exception {
        open(FIRST, SECOND);
        edt(() -> view.createButton().doClick());

        edt(() -> view.nameField().setText("Bo"));

        assertFalse(onEdt(() -> view.backstoryPanel().isVisible()));
        assertFalse(onEdt(() -> view.startButton().isEnabled()));
        assertFalse(onEdt(() -> view.regenerateButton().isEnabled()));
        assertTrue(onEdt(() -> view.createButton().isEnabled()));
        edt(() -> view.createButton().doClick());
        assertEquals(SECOND, onEdt(() -> view.backstoryArea().getText()));
        edt(() -> view.startButton().doClick());
        assertEquals("Bo", controller.getState().getPlayer().getName());
    }

    @Test
    void offlineTheTemplateIsShownWithTheNoticeOnTheNewGameScreen() throws Exception {
        open();

        edt(() -> view.createButton().doClick());

        assertTrue(onEdt(() -> view.backstoryArea().getText()).startsWith("Ari learned early that locks"));
        assertEquals("(The backstory is pre-written. Reason: LLM unavailable.)",
                onEdt(() -> view.newGameNotice().getText()));
        assertEquals("", onEdt(() -> view.storyArea().getText()), "the notice is not in the story panel");
        edt(() -> view.startButton().doClick());
        String story = onEdt(() -> view.storyArea().getText());
        assertTrue(story.startsWith(GuiText.help("offline")), story);
    }

    @Test
    void anInvalidFormCannotGenerate() throws Exception {
        open();
        edt(() -> view.statSliders().get(0).setValue(5));

        assertFalse(onEdt(() -> view.createButton().isEnabled()));
        assertEquals(GameController.STATS_INVALID, onEdt(() -> view.newGameMessage().getText()));
        assertEquals(List.of(), controller.calls);
    }
}
