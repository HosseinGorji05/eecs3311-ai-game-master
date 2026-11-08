package presentation;

import engine.character.CharacterClass;
import engine.character.Stats;
import engine.core.Ending;
import engine.core.FixedDiceRoller;
import engine.core.GameState;
import engine.persistence.SaveManager;
import engine.quests.Quest;
import engine.world.Location;
import llm.ScriptedLLMClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A command the engine understood but refused is answered with the engine's own short reason, not with
 * "I didn't understand" (F02, UC02 4a), whatever the model answered; "I didn't understand" is left for text
 * that is no action. The model's replies are scripted; nothing uses the network.
 */
class RejectionReasonTest {

    @TempDir
    Path saves;

    /** Records what the controller shows. */
    private static final class Shown implements GameView {
        final List<String> lines = new ArrayList<>();

        @Override
        public void render(GameState state) {
        }

        @Override
        public void showNarration(String text) {
            lines.add(text);
        }

        @Override
        public void showNotice(String text) {
            lines.add("(" + text + ")");
        }

        @Override
        public void showError(String message) {
            lines.add("! " + message);
        }

        @Override
        public void showQuestOffer(Quest quest) {
        }

        @Override
        public void showEnding(Ending ending) {
        }
    }

    private final Shown shown = new Shown();

    /** A warrior in the Village Square; the model gives these answers, the dice these rolls. */
    private GameController start(List<String> answers, int... rolls) {
        GameController controller = new GameController(new ScriptedLLMClient(answers), new FixedDiceRoller(rolls),
                new SaveManager(saves));
        controller.addView(shown);
        controller.newGame("Ari", CharacterClass.WARRIOR, new Stats(5, 3, 2));
        controller.startGame();
        return controller;
    }

    private static void moveTo(GameController controller, String id) {
        Location location = controller.getState().getWorld().findLocation(id);
        controller.getState().setCurrentLocation(location);
        location.markVisited();
    }

    @Test
    void eastAtTheLockedDoorSaysWhichKeyIsNeededEvenWhenTheModelProposesNothing() {
        GameController controller = start(List.of("{\"actions\":[]}"));

        TurnResult turn = controller.submitCommand("east");

        assertEquals("The way to Tool Shed is locked. You need the shed key.", turn.getNarration());
        assertFalse(shown.lines.stream().anyMatch(line -> line.contains("didn't understand")), shown.lines.toString());
        assertFalse(shown.lines.stream().anyMatch(line -> line.contains("Basic-command mode")),
                "the model was available, so no basic-mode notice: " + shown.lines);
    }

    @Test
    void eastAtTheLockedDoorAsTheModelTranslatesIt() {
        GameController controller = start(List.of("{\"actions\":[{\"type\":\"move\",\"target\":\"east\"}]}"));

        assertEquals("The way to Tool Shed is locked. You need the shed key.", controller.submitCommand("east").getNarration());
    }

    @Test
    void goingNorthDuringAFightSaysToAttackOrFlee() {
        GameController controller = start(List.of("{\"actions\":[{\"type\":\"attack\",\"target\":\"wolf\"}]}",
                "{\"narration\":\"You swing at the wolf and miss. The wolf misses you as well.\"}",
                "{\"actions\":[{\"type\":\"move\",\"target\":\"north\"}]}"), 1, 1);
        moveTo(controller, "wolf-den");
        controller.submitCommand("attack wolf");

        TurnResult turn = controller.submitCommand("go north");

        assertEquals("You can't leave during the fight with the wolf. Attack it, or flee.", turn.getNarration());
    }

    @Test
    void talkingToSomeoneInAnotherPlaceSaysTheyAreNotHere() {
        GameController controller = start(List.of("{\"actions\":[{\"type\":\"talk\",\"target\":\"Brann\"}]}",
                "{\"narration\":\"You look around for Brann. Brann works at the forge, not here.\"}",
                "{\"actions\":[{\"type\":\"talk\",\"target\":\"the blacksmith\"}]}"));

        assertEquals("Brann isn't here.\nYou look around for Brann. Brann works at the forge, not here.",
                controller.submitCommand("Brann").getNarration(), "the engine's reason, then the narration");
        assertEquals("Brann isn't here.", controller.submitCommand("talk to the blacksmith").getNarration());
    }

    @Test
    void talkingToSomeoneWhoDoesNotExistStillSaysThereIsNoSuchPerson() {
        GameController controller = start(List.of("{\"actions\":[{\"type\":\"talk\",\"target\":\"the dragon\"}]}"));

        assertEquals("There is no dragon here.", controller.submitCommand("talk to the dragon").getNarration());
    }

    @Test
    void attackingAWolfThatIsNotHereSaysSoWithTheModelAndWithoutIt() {
        GameController withModel = start(List.of("{\"actions\":[{\"type\":\"attack\",\"target\":\"the wolf\"}]}"));
        GameController offline = start(List.of());

        assertEquals("There is no wolf here.", withModel.submitCommand("attack the wolf").getNarration());
        assertEquals("There is no wolf here.", offline.submitCommand("attack the wolf").getNarration());
    }

    @Test
    void textThatIsNoActionIsStillNotUnderstood() {
        GameController controller = start(List.of("{\"actions\":[]}"));

        TurnResult turn = controller.submitCommand("what a lovely morning");

        assertEquals(GameController.NOT_UNDERSTOOD, turn.getNarration());
        assertTrue(turn.getResults().isEmpty());
    }

    @Test
    void anUnparsableReplyNeverReachesThePlayer() {
        String reply = "Sure! I think you want to go east, but the door is locked.";
        GameController controller = start(List.of(reply, reply));

        TurnResult turn = controller.submitCommand("east");

        assertEquals("The way to Tool Shed is locked. You need the shed key.", turn.getNarration());
        assertFalse(String.join("\n", shown.lines).contains("Sure!"), shown.lines.toString());
    }

    @Test
    void theCliPrintsTheReason() {
        GameController controller = new GameController(new ScriptedLLMClient(List.of("{\"actions\":[]}")),
                new FixedDiceRoller(), new SaveManager(saves));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CliGameView cli = new CliGameView(controller,
                new ByteArrayInputStream("Ari\nwarrior\neast\nquit\n".getBytes(StandardCharsets.UTF_8)),
                new PrintStream(bytes, true, StandardCharsets.UTF_8));
        controller.addView(cli);

        cli.readLoop();

        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("The way to Tool Shed is locked. You need the shed key."), output);
        assertFalse(output.contains("didn't understand"), output);
    }
}
