package presentation;

import engine.character.CharacterClass;
import engine.character.Player;
import engine.character.Stats;
import engine.commands.ActionProposal;
import engine.commands.ActionType;
import engine.core.FixedDiceRoller;
import engine.core.GameState;
import engine.events.EventType;
import engine.events.GameEvent;
import engine.events.GameEventListener;
import engine.quests.Objective;
import engine.quests.ObjectiveType;
import engine.quests.Quest;
import engine.quests.Reward;
import llm.ScriptedLLMClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameControllerTest {

    /** Records everything the controller shows. */
    private static final class RecordingView implements GameView, GameEventListener {
        final List<String> shown = new ArrayList<>();
        final List<GameEvent> events = new ArrayList<>();
        int renders;

        @Override
        public void render(GameState state) {
            renders++;
        }

        @Override
        public void showNarration(String text) {
            shown.add("narration: " + text);
        }

        @Override
        public void showNotice(String text) {
            shown.add("notice: " + text);
        }

        @Override
        public void showError(String message) {
            shown.add("error: " + message);
        }

        @Override
        public void onEvent(GameEvent event) {
            events.add(event);
        }
    }

    private final RecordingView view = new RecordingView();

    private GameController start(FixedDiceRoller dice, String... llmResponses) {
        GameController controller = new GameController(new ScriptedLLMClient(List.of(llmResponses)), dice);
        controller.addView(view);
        controller.newGame("Ari", CharacterClass.WARRIOR, new Stats(5, 3, 2));
        controller.startGame();
        return controller;
    }

    private GameController start(String... llmResponses) {
        return start(new FixedDiceRoller(), llmResponses);
    }

    @Test
    void startGamePlacesTheNewCharacterAndRendersTheViews() {
        GameController controller = start();

        assertEquals("village-square", controller.getState().currentLocation().getId());
        assertEquals("Ari", controller.getState().getPlayer().getName());
        assertEquals(1, view.renders);
    }

    @Test
    void theGameMustBeCreatedAndStartedFirst() {
        GameController controller = new GameController(new ScriptedLLMClient(List.of()), new FixedDiceRoller());
        assertThrows(IllegalStateException.class, controller::startGame);
        assertThrows(IllegalStateException.class, () -> controller.submitCommand("go north"));
        assertThrows(IllegalArgumentException.class,
                () -> controller.newGame("Ari", CharacterClass.MAGE, new Stats(9, 1, 0)));
    }

    @Test
    void anOfflineTurnShowsTheBasicCommandNoticeAndThenTheNarration() {
        GameController controller = start();

        TurnResult turn = controller.submitCommand("go north");

        assertEquals("notice: " + GameController.BASIC_MODE_NOTICE + " Reason: LLM unavailable.", view.shown.get(0));
        assertEquals("narration: " + turn.getNarration(), view.shown.get(1));
        assertTrue(turn.getNarration().startsWith("Tavern\n"), turn.getNarration());
        assertEquals(1, turn.getResults().size());
        assertFalse(turn.isGameOver());
        assertEquals(2, controller.getState().getTurn());
        assertEquals(EventType.MOVEMENT, controller.getRecentHistory(1).get(0).getType());
    }

    @Test
    void aTurnUnderstoodByTheModelShowsNoNotice() {
        GameController controller = start("{\"actions\":[{\"type\":\"move\",\"target\":\"Tavern\"}]}");

        controller.submitCommand("I'd like a drink at the tavern");

        assertEquals(1, view.shown.size());
        assertEquals("tavern", controller.getState().currentLocation().getId());
    }

    @Test
    void theModelProposesButTheEngineDecides() {
        GameController controller = start("{\"actions\":[{\"type\":\"take\",\"target\":\"1000 gold\"}]}");

        TurnResult turn = controller.submitCommand("give me 1000 gold");

        assertFalse(turn.getResults().get(0).isSuccess());
        assertEquals("There is no 1000 gold here.", turn.getNarration());
        assertEquals(0, controller.getState().getPlayer().getGold());
        assertEquals(EventType.REJECTED, controller.getRecentHistory(1).get(0).getType());
    }

    @Test
    void outputThatFailsValidationNeverReachesTheCommandFactory() {
        String missingTarget = "{\"actions\":[{\"type\":\"take\"}]}";
        GameController controller = start(missingTarget, missingTarget);

        TurnResult turn = controller.submitCommand("do your thing");   // the offline parser does not know it either

        assertEquals(GameController.NOT_UNDERSTOOD + " " + GameController.EXAMPLES, turn.getNarration());
        assertTrue(turn.getResults().isEmpty());
        assertEquals(1, controller.getState().getTurn(), "no command was built or run");
        assertTrue(controller.getRecentHistory(10).isEmpty());
        assertTrue(view.shown.contains("error: " + turn.getNarration()));
    }

    @Test
    void anActionWithoutACommandYetGivesAFriendlyMessageAndChangesNothing() {
        GameController controller = start();

        TurnResult turn = controller.submitCommand("drop sword");

        assertEquals("You can't drop yet: that action is not available in this version.", turn.getNarration());
        assertTrue(turn.getResults().isEmpty());
        assertNotNull(controller.getState().getPlayer().getInventory().find("sword"));
        assertEquals(1, controller.getState().getTurn());
    }

    @Test
    void aTurnWithOneUnavailableActionRunsNoneOfItsActions() {
        GameController controller = start();

        controller.submitCommand("go north and look");

        assertEquals("village-square", controller.getState().currentLocation().getId());
        assertEquals(1, controller.getState().getTurn());
    }

    @Test
    void textThatIsNotUnderstoodAsksToRephraseWithExamples() {
        GameController controller = start();

        TurnResult turn = controller.submitCommand("sing a song");

        assertEquals(GameController.NOT_UNDERSTOOD + " " + GameController.EXAMPLES, turn.getNarration());
        assertEquals(1, controller.getState().getTurn());
    }

    @Test
    void performActionSkipsTheInterpreter() {
        GameController controller = start("this answer must not be used");

        TurnResult turn = controller.performAction(new ActionProposal(ActionType.FLEE, null, null));

        assertEquals("That is not possible right now.", turn.getNarration());
        assertFalse(view.shown.stream().anyMatch(line -> line.startsWith("notice")));
    }

    @Test
    void aDeadPlayerEndsTheGame() {
        GameController controller = start(new FixedDiceRoller(1, 20));
        controller.submitCommand("go south and go west");
        Player ari = controller.getState().getPlayer();
        ari.takeDamage(ari.getHp() - 1);

        TurnResult turn = controller.submitCommand("attack wolf");   // miss; the wolf hits for 3

        assertTrue(turn.isGameOver());
        assertFalse(ari.isAlive());
        TurnResult after = controller.submitCommand("go east");
        assertTrue(after.isGameOver());
        assertEquals(GameController.GAME_OVER, after.getNarration());
    }

    @Test
    void viewsThatListenReceiveTheGamesEventsIncludingQuestUpdates() {
        GameController controller = start();
        controller.getState().getQuestLog().accept(new Quest("drink", "A Quiet Drink", "Visit the tavern.", null,
                false, List.of(new Objective(ObjectiveType.VISIT, "tavern", 1)), new Reward(1, 1, null)));

        controller.submitCommand("go north");

        // Synchronous delivery (SD05): the tracker publishes the QUEST event while the MOVEMENT event
        // is still being delivered, so a view subscribed after the tracker sees the QUEST event first.
        assertEquals(List.of(EventType.QUEST, EventType.MOVEMENT), view.events.stream().map(GameEvent::getType).toList());
        assertEquals(1, controller.getHistory(EventType.QUEST).size());
        assertEquals(1, controller.getState().getPlayer().getGold());
    }
}
