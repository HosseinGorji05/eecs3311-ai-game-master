package agent;

import engine.character.CharacterClass;
import engine.character.CharacterFactory;
import engine.character.Enemy;
import engine.character.Stats;
import engine.commands.ActionProposal;
import engine.commands.ActionType;
import engine.core.FixedDiceRoller;
import engine.core.GameState;
import engine.items.Consumable;
import engine.world.World;
import llm.LLMClient;
import llm.LlmRequest;
import llm.LlmResponse;
import llm.ScriptedLLMClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import presentation.GameController;
import presentation.GameView;
import presentation.TurnResult;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shapes real models answer in, from canned replies (no network): each must be read as the same
 * actions instead of ending in the offline fallback.
 */
class ActionInterpreterReplyShapesTest {

    private static final String MOVE_NORTH_AND_TAKE = "{\"actions\":[{\"type\":\"move\",\"target\":\"north\"},"
            + "{\"type\":\"take\",\"target\":\"shed key\",\"secondTarget\":null}]}";

    private GameState state;

    @BeforeEach
    void startGame() {
        state = new GameState();
        state.start(CharacterFactory.forClass(CharacterClass.WARRIOR).createCharacter("Ari", new Stats(5, 3, 2)),
                World.createDefault());
    }

    private ActionInterpreter interpreter(String... replies) {
        return new ActionInterpreter(new ScriptedLLMClient(List.of(replies)), new PromptBuilder(),
                new ResponseParser(), state);
    }

    /** The reply read on the first attempt, without the fallback. */
    private List<ActionProposal> firstAttempt(String reply) {
        ActionInterpreter interpreter = interpreter(reply);
        List<ActionProposal> proposals = interpreter.interpret("make your way north and grab the shed key");
        assertFalse(interpreter.isFallbackUsed(), "fell back on: " + reply);
        return proposals;
    }

    private static void assertMoveNorthAndTakeShedKey(List<ActionProposal> proposals) {
        assertEquals(2, proposals.size());
        assertEquals(ActionType.MOVE, proposals.get(0).getType());
        assertEquals("north", proposals.get(0).getTarget());
        assertEquals(ActionType.TAKE, proposals.get(1).getType());
        assertEquals("shed key", proposals.get(1).getTarget());
        assertNull(proposals.get(1).getSecondTarget());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            MOVE_NORTH_AND_TAKE,
            "```json\n" + MOVE_NORTH_AND_TAKE + "\n```",
            "```\n" + MOVE_NORTH_AND_TAKE + "\n```",
            "```JSON " + MOVE_NORTH_AND_TAKE + "```",
            "Here are the actions:\n```json\n" + MOVE_NORTH_AND_TAKE + "\n```\nHave fun!",
            "Sure! " + MOVE_NORTH_AND_TAKE,
            MOVE_NORTH_AND_TAKE + "\nI moved you north and took the key.",
            "The JSON is: " + MOVE_NORTH_AND_TAKE + " (as requested {ok})",
            "  \n" + MOVE_NORTH_AND_TAKE + "  \n"})
    void fencesAndTextAroundTheJsonAreIgnored(String reply) {
        assertMoveNorthAndTakeShedKey(firstAttempt(reply));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"actions\":[{\"type\":\"move\",\"target\":\"north\"},{\"type\":\"take\",\"target\":\"shed key\"}]}",
            "{\"Actions\":[{\"Type\":\"move\",\"Target\":\"north\"},{\"TYPE\":\"take\",\"TARGET\":\"shed key\"}]}",
            "{\"actions\":[{\"action\":\"move\",\"target\":\"north\"},{\"action\":\"take\",\"object\":\"shed key\"}]}",
            "{\"actions\":[{\"action_type\":\"move\",\"target\":\"north\"},"
                    + "{\"actionType\":\"take\",\"target\":\"shed key\",\"second_target\":null}]}",
            "[{\"type\":\"move\",\"target\":\"north\"},{\"type\":\"take\",\"target\":\"shed key\"}]"})
    void fieldNamesAreMatchedIgnoringCaseAndCommonVariants(String reply) {
        assertMoveNorthAndTakeShedKey(firstAttempt(reply));
    }

    @ParameterizedTest
    @ValueSource(strings = {"move", "MOVE", "Move", " move ", "mOvE"})
    void actionTypesIgnoreCase(String type) {
        List<ActionProposal> proposals = firstAttempt("{\"actions\":[{\"type\":\"" + type + "\",\"target\":\"north\"}]}");

        assertEquals(ActionType.MOVE, proposals.get(0).getType());
    }

    @Test
    void aSingleActionObjectIsOneAction() {
        List<ActionProposal> proposals = firstAttempt("{\"type\":\"attack\",\"target\":\"wolf\"}");

        assertEquals(1, proposals.size());
        assertEquals(ActionType.ATTACK, proposals.get(0).getType());
        assertEquals("wolf", proposals.get(0).getTarget());
    }

    @Test
    void aPutWithTheContainerFieldKeepsBothTargets() {
        List<ActionProposal> proposals = firstAttempt(
                "{\"actions\":[{\"type\":\"put\",\"target\":\"bread\",\"container\":\"pouch\"}]}");

        assertEquals("bread", proposals.get(0).getTarget());
        assertEquals("pouch", proposals.get(0).getSecondTarget());
    }

    @Test
    void aLocationNameAsTheMoveTargetIsLeftToTheEngine() {
        List<ActionProposal> proposals = firstAttempt("{\"actions\":[{\"type\":\"move\",\"target\":\"Tavern\"}]}");

        assertEquals("Tavern", proposals.get(0).getTarget());
    }

    @Test
    void aLocationNameTheModelCopiedIsMovedToByTheEngine() {
        GameController controller = new GameController(new ScriptedLLMClient(List.of(
                "```json\n{\"Actions\":[{\"Type\":\"MOVE\",\"Target\":\"Tavern\"}]}\n```")), new FixedDiceRoller());
        List<String> notices = new ArrayList<>();
        controller.addView(new GameView() {
            @Override
            public void render(GameState shown) {
            }

            @Override
            public void showNarration(String text) {
            }

            @Override
            public void showNotice(String text) {
                notices.add(text);
            }

            @Override
            public void showError(String message) {
            }
        });
        controller.newGame("Ari", CharacterClass.WARRIOR, new Stats(5, 3, 2));
        controller.startGame();

        TurnResult turn = controller.submitCommand("make your way to the tavern");

        assertTrue(notices.isEmpty(), "no basic-command notice: " + notices);
        assertEquals("tavern", controller.getState().currentLocation().getId(), turn.getNarration());
    }

    @Test
    void anUnusableReplyNamesTheReasonAndShowsTheValidationErrorInDebugOutput() {
        ActionInterpreter interpreter = interpreter(
                "{\"actions\":[{\"type\":\"take\"}]}",
                "Sorry, I can't help with that.");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        interpreter.setDebugOutput(new PrintStream(bytes, true, StandardCharsets.UTF_8));

        interpreter.interpret("take shed key");

        assertTrue(interpreter.isFallbackUsed());
        assertEquals("invalid output", interpreter.getFallbackReason());
        String debug = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(debug.contains("attempt 1/2 failed: invalid output - take needs a target"), debug);
        assertTrue(debug.contains("[LLM_DEBUG] reply: {\"actions\":[{\"type\":\"take\"}]}"), debug);
        assertTrue(debug.contains("attempt 2/2 failed: invalid output - the reply could not be parsed"), debug);
        assertTrue(debug.contains("[LLM_DEBUG] reply: Sorry, I can't help with that."), debug);
        assertFalse(debug.contains("Game state:"), "the prompt is never printed");
    }

    @Test
    void theValidationErrorNamesTheBrokenRule() {
        ActionInterpreter interpreter = interpreter();
        List<ActionProposal> six = List.of(look(), look(), look(), look(), look(), look());

        assertEquals("too many actions (6, at most 5)", interpreter.describeInvalid(six, "x"));
        assertEquals("put needs a container (secondTarget)",
                interpreter.describeInvalid(List.of(new ActionProposal(ActionType.PUT, "bread", null)), "x"));
        assertEquals("move has a target longer than 60 characters", interpreter.describeInvalid(
                List.of(new ActionProposal(ActionType.MOVE, "n".repeat(61), null)), "x"));
    }

    @Test
    void thePromptGivesTheSchemaTheTypesAndTheNearbyNames() {
        List<LlmRequest> requests = new ArrayList<>();
        LLMClient recording = new LLMClient() {
            @Override
            public LlmResponse complete(LlmRequest request) {
                requests.add(request);
                return new LlmResponse("{\"actions\":[]}", true, null);
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String providerName() {
                return "recording";
            }
        };
        state.currentLocation().addItem(new Consumable("apple", 0.1, 1));
        state.currentLocation().addEnemy(new Enemy("rat", 3, 1, 8));
        new ActionInterpreter(recording, new PromptBuilder(), new ResponseParser(), state).interpret("look around");

        String system = requests.get(0).getSystemPrompt();
        assertTrue(system.contains("{\"actions\":[{\"type\":\"<type>\",\"target\":\"<name or null>\","
                + "\"secondTarget\":\"<name or null>\"}]}"), system);
        for (ActionType type : ActionType.values()) {
            assertTrue(system.contains(type.name().toLowerCase()), "missing type " + type);
        }
        assertTrue(system.contains("no code fence"), system);
        String user = requests.get(0).getUserPrompt();
        assertTrue(user.contains("Items here: apple"), user);
        assertTrue(user.contains("Enemies here: rat (3/3 HP)"), user);
        assertTrue(user.contains("north (Tavern)"), user);
        assertTrue(user.contains("east (Tool Shed, locked)"), user);
    }

    private static ActionProposal look() {
        return new ActionProposal(ActionType.LOOK, null, null);
    }
}
