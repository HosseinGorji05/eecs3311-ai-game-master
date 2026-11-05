package presentation;

import engine.character.CharacterClass;
import engine.character.Stats;
import engine.commands.ActionProposal;
import engine.commands.ActionType;
import engine.core.Ending;
import engine.core.FixedDiceRoller;
import engine.core.GameState;
import engine.events.EventType;
import engine.events.GameEvent;
import engine.events.GameEventListener;
import engine.quests.Quest;
import llm.ScriptedLLMClient;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A view whose event handling throws does not break the turn, the log, the memory or the quests. */
class ObserverFailureTurnTest {

    /** Shows narration but throws on every event. */
    private static final class BrokenView implements GameView, GameEventListener {
        final List<String> narration = new ArrayList<>();

        @Override
        public void onEvent(GameEvent event) {
            throw new IllegalStateException("broken panel");
        }

        @Override
        public void render(GameState state) {
        }

        @Override
        public void showNarration(String text) {
            narration.add(text);
        }

        @Override
        public void showNotice(String text) {
        }

        @Override
        public void showError(String message) {
        }

        @Override
        public void showQuestOffer(Quest quest) {
        }

        @Override
        public void showEnding(Ending ending) {
        }
    }

    @Test
    void theTurnFinishesAndTheFailureIsReportedOnceOnStandardError() {
        PrintStream realErr = System.err;
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            GameController controller = new GameController(new ScriptedLLMClient(List.of()), new FixedDiceRoller());
            BrokenView view = new BrokenView();
            controller.addView(view);
            controller.newGame("Ari", CharacterClass.WARRIOR, new Stats(5, 3, 2));
            controller.startGame();

            TurnResult turn = controller.performAction(new ActionProposal(ActionType.MOVE, "north", null));

            assertEquals("tavern", controller.getState().currentLocation().getId());
            assertEquals(2, controller.getState().getTurn(), "the turn advanced");
            assertTrue(turn.getNarration().startsWith("Tavern"), turn.getNarration());
            assertEquals(List.of(turn.getNarration()), view.narration);
            assertEquals(1, controller.getHistory(EventType.MOVEMENT).size(), "the event log still recorded the move");
            assertTrue(controller.getJournal().contains("moved to Tavern"), "the memory still recorded it");
            String reported = err.toString(StandardCharsets.UTF_8);
            assertEquals(1, reported.lines().count(), reported);
            assertTrue(reported.startsWith("[event bus] BrokenView failed on the MOVEMENT event of turn 1"), reported);
            assertTrue(reported.contains("IllegalStateException: broken panel"), reported);
        } finally {
            System.setErr(realErr);
        }
    }
}
