package presentation;

import engine.character.CharacterClass;
import engine.character.Stats;
import engine.commands.ActionProposal;
import engine.commands.ActionResult;
import engine.commands.ActionType;
import engine.core.Ending;
import engine.core.EndingType;
import engine.core.FixedDiceRoller;
import engine.core.GameState;
import engine.events.EventType;
import engine.events.GameEvent;
import engine.quests.Quest;
import engine.world.Location;
import llm.LLMClient;
import llm.LlmRequest;
import llm.LlmResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where narration happens (F02, F04, SD02): typed turns are narrated after the engine's own text, buttons and
 * dialogue never are, a failure shows the turn as before with at most one notice, and the ending is unchanged.
 */
class GameControllerNarrationTest {

    /** Answers with the next scripted response (an error text starting with "!" is a failed call) and counts calls. */
    private static final class Client implements LLMClient {
        private final List<String> answers;
        int calls;

        Client(String... answers) {
            this.answers = new ArrayList<>(List.of(answers));
        }

        @Override
        public LlmResponse complete(LlmRequest request) {
            calls++;
            if (answers.isEmpty()) {
                return new LlmResponse(null, false, "No scripted response left");
            }
            String answer = answers.remove(0);
            return answer.startsWith("!") ? new LlmResponse(null, false, answer.substring(1))
                    : new LlmResponse(answer, true, null);
        }

        @Override
        public boolean isAvailable() {
            return !answers.isEmpty();
        }

        @Override
        public String providerName() {
            return "scripted";
        }
    }

    private final List<String> shown = new ArrayList<>();
    private final List<Ending> endings = new ArrayList<>();

    private final GameView view = new GameView() {
        @Override
        public void render(GameState state) {
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
        public void showQuestOffer(Quest quest) {
        }

        @Override
        public void showEnding(Ending ending) {
            endings.add(ending);
            shown.add("ending: " + ending.getType());
        }
    };

    private GameController start(Client client, int... rolls) {
        GameController controller = new GameController(client, new FixedDiceRoller(rolls));
        controller.addView(view);
        controller.newGame("Ari", CharacterClass.WARRIOR, new Stats(5, 3, 2));
        controller.startGame();
        return controller;
    }

    private static String narration(String text) {
        return "{\"narration\":\"" + text + "\"}";
    }

    private static void moveTo(GameController controller, String id) {
        Location location = controller.getState().getWorld().findLocation(id);
        controller.getState().setCurrentLocation(location);
        location.markVisited();
    }

    private static final String MOVE_NORTH = "{\"actions\":[{\"type\":\"move\",\"target\":\"north\"}]}";
    private static final String TAVERN_NARRATION = "You push open the door of the Tavern. Marta looks up from the bar.";

    @Test
    void aTypedMoveIsNarratedAfterTheEnginesOwnText() {
        GameController controller = start(new Client(MOVE_NORTH, narration(TAVERN_NARRATION)));

        TurnResult turn = controller.submitCommand("go north");

        String engineText = turn.getResults().get(0).getMessage();
        assertTrue(engineText.startsWith("Tavern\nA smoky room"), engineText);
        assertTrue(engineText.endsWith("Exits: south (Village Square)"), engineText);
        assertEquals(engineText + "\n" + TAVERN_NARRATION, turn.getNarration());
        assertEquals(List.of("narration: " + turn.getNarration()), shown, "no notice");
        assertEquals("moved to Tavern", controller.getHistory(EventType.MOVEMENT).get(0).getDescription());
        assertFalse(controller.getJournal().contains("Marta looks up"), "the narration is not recorded");
        assertTrue(controller.getState().getEventLog().recent(100).stream().noneMatch(
                event -> event.getDescription().contains("push open")), "nor logged");
    }

    @Test
    void aTypedAttackKeepsTheEnginesRollsAndEventsAndAddsTheNarration() {
        String said = "You strike the wolf for 2 damage. It snaps at you and misses.";
        GameController controller = start(new Client("{\"actions\":[{\"type\":\"attack\",\"target\":\"wolf\"}]}",
                narration(said)), 20, 1);
        moveTo(controller, "wolf-den");

        TurnResult turn = controller.submitCommand("attack the wolf");

        ActionResult attack = turn.getResults().get(0);
        String rolls = "Ari attacks wolf: rolls 20 + 5 = 25 against defense 11. Hit for 2 damage (wolf: 6/8 HP).\n"
                + "wolf attacks Ari: rolls 1 + 3 = 4 against defense 13. Miss.";
        assertEquals(rolls, attack.getMessage());
        assertEquals(rolls + "\n" + said, turn.getNarration());
        List<String> combat = controller.getHistory(EventType.COMBAT).stream().map(GameEvent::getDescription).toList();
        assertEquals(List.of("Ari attacks wolf: rolls 20 + 5 = 25 against defense 11. Hit for 2 damage (wolf: 6/8 HP).",
                "wolf attacks Ari: rolls 1 + 3 = 4 against defense 13. Miss."), combat, "what the combat panel reads");
    }

    @Test
    void buttonsMapClicksAndFixedWordsAreNeverNarrated() {
        Client client = new Client(narration(TAVERN_NARRATION));
        GameController controller = start(client);
        int callsAfterStart = client.calls;

        TurnResult move = controller.performAction(new ActionProposal(ActionType.MOVE, "Tavern", null));
        controller.performAction(new ActionProposal(ActionType.TAKE, "bread", null));

        assertEquals(callsAfterStart, client.calls, "no model call at all");
        assertEquals(move.getResults().get(0).getMessage(), move.getNarration());
        assertTrue(shown.stream().noneMatch(line -> line.startsWith("notice")), shown.toString());
    }

    @Test
    void dialogueIsNeverNarrated() {
        Client client = new Client("{\"text\":\"Busy night. What do you need?\",\"sideEffect\":null}",
                narration("This must never be asked for. Nobody narrates dialogue."));
        GameController controller = start(client);
        moveTo(controller, "tavern");
        controller.performAction(new ActionProposal(ActionType.TALK, "Marta", null));

        controller.sendDialogue("Hello");

        assertEquals(1, client.calls, "only the NPC's reply");
        assertTrue(shown.contains("narration: Marta: \"Busy night. What do you need?\""), shown.toString());
    }

    @Test
    void aFailedNarrationShowsTheTurnAsBeforeWithOneNotice() {
        GameController controller = start(new Client(MOVE_NORTH, narration("You fly to the Moon. Gregor cheers."),
                narration("You hit the bar. Everyone dies.")));

        TurnResult turn = controller.submitCommand("go north");

        assertEquals(turn.getResults().get(0).getMessage(), turn.getNarration());
        assertEquals(List.of("notice: The narration is pre-written. Reason: invalid output.", "narration: " + turn.getNarration()),
                shown);
    }

    @Test
    void aRejectedKeyIsNamedOnceAndNotAskedAgain() {
        Client client = new Client(MOVE_NORTH, "!HTTP 401 (API key rejected)", narration(TAVERN_NARRATION));

        GameController controller = start(client);
        int before = client.calls;
        controller.submitCommand("go north");

        assertEquals(before + 2, client.calls, "the interpreter's call and one narrator call");
        assertEquals(1, shown.stream().filter(line -> line.startsWith("notice")).count(), shown.toString());
        assertTrue(shown.contains("notice: The narration is pre-written. Reason: LLM unavailable: HTTP 401 (API key rejected)."));
    }

    @Test
    void theBasicCommandNoticeIsTheOnlyNoticeOfTheTurn() {
        GameController controller = start(new Client("nonsense", "more nonsense", "{\"narration\":\"Bad.\"}",
                "{\"narration\":\"Worse.\"}"));

        TurnResult turn = controller.submitCommand("go north");

        List<String> notices = shown.stream().filter(line -> line.startsWith("notice")).toList();
        assertEquals(List.of("notice: Basic-command mode: simple commands only. Reason: invalid output."), notices);
        assertEquals(turn.getResults().get(0).getMessage(), turn.getNarration());
    }

    @Test
    void offlineTheTurnLooksExactlyAsWithoutANarrator() {
        GameController controller = start(new Client());

        TurnResult turn = controller.submitCommand("go north");

        assertEquals(List.of("notice: Basic-command mode: simple commands only. Reason: LLM unavailable.",
                "narration: " + turn.getResults().get(0).getMessage()), shown);
        assertEquals(turn.getResults().get(0).getMessage(), turn.getNarration());
    }

    @Test
    void aDeathIsNarratedAndThenTheEndingRunsAsBefore() {
        String said = "You swing at the wolf and miss. The wolf strikes back and you fall.";
        GameController controller = start(new Client("{\"actions\":[{\"type\":\"attack\",\"target\":\"wolf\"}]}",
                narration(said)), 1, 20);
        moveTo(controller, "wolf-den");
        controller.getState().getPlayer().takeDamage(controller.getState().getPlayer().getHp() - 1);

        TurnResult turn = controller.submitCommand("attack wolf");

        assertTrue(turn.isGameOver());
        assertTrue(turn.getNarration().endsWith("You die.\n" + said), turn.getNarration());
        assertEquals(EndingType.DEATH, endings.get(0).getType());
        int narrationAt = shown.indexOf("narration: " + turn.getNarration());
        assertTrue(narrationAt >= 0 && narrationAt < shown.indexOf("ending: DEATH"), shown.toString());
    }
}
