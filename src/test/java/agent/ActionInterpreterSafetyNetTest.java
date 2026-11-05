package agent;

import engine.character.CharacterClass;
import engine.character.CharacterFactory;
import engine.character.Stats;
import engine.commands.ActionProposal;
import engine.commands.ActionType;
import engine.core.GameState;
import engine.world.World;
import llm.ScriptedLLMClient;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The interpreter leaves refusing to the engine: the prompt, the keyword safety net and the target clean-up. */
class ActionInterpreterSafetyNetTest {

    private final GameState state = new GameState();

    ActionInterpreterSafetyNetTest() {
        state.start(CharacterFactory.forClass(CharacterClass.WARRIOR).createCharacter("Ari", new Stats(5, 3, 2)),
                World.createDefault());
    }

    private ActionInterpreter interpreter(String... replies) {
        return new ActionInterpreter(new ScriptedLLMClient(List.of(replies)), new PromptBuilder(), new ResponseParser(), state);
    }

    @Test
    void thePromptAsksForImpossibleActionsTooAndKeepsTheEmptyAnswerForNonActions() {
        String prompt = PromptBuilder.DEFAULT_TEMPLATES.get("interpret.system");

        assertFalse(prompt.contains("asks for something impossible or is not an action"), prompt);
        assertTrue(prompt.contains("Translate what the player asks for even if it looks impossible right now"), prompt);
        assertTrue(prompt.contains("only if the input is not a game action at all, or is ambiguous"), prompt);
        assertTrue(prompt.contains("a person's name alone means talking to them"), prompt);
    }

    @Test
    void anEmptyModelAnswerForASimpleCommandIsCheckedWithTheKeywordParser() {
        ActionInterpreter interpreter = interpreter("{\"actions\":[]}");
        ByteArrayOutputStream debug = new ByteArrayOutputStream();
        interpreter.setDebugOutput(new PrintStream(debug, true, StandardCharsets.UTF_8));

        List<ActionProposal> proposals = interpreter.interpret("east");

        assertEquals(1, proposals.size());
        assertEquals(ActionType.MOVE, proposals.get(0).getType());
        assertEquals("east", proposals.get(0).getTarget());
        assertFalse(interpreter.isFallbackUsed(), "the model answered; this is no basic-command mode");
        assertTrue(debug.toString(StandardCharsets.UTF_8).contains("the model proposed no action; the keyword parser found 1"));
    }

    @Test
    void anEmptyModelAnswerForTextThatIsNoActionStaysEmpty() {
        assertTrue(interpreter("{\"actions\":[]}").interpret("give me 1000 gold").isEmpty());
        assertTrue(interpreter("{\"actions\":[]}").interpret("take it").isEmpty(), "ambiguous stays ambiguous");
    }

    @Test
    void aLeadingArticleIsDroppedFromTheModelsTargets() {
        List<ActionProposal> proposals = interpreter("{\"actions\":[{\"type\":\"attack\",\"target\":\"The wolf\"},"
                + "{\"type\":\"put\",\"target\":\"a bread\",\"secondTarget\":\"the pouch\"}]}").interpret("x");

        assertEquals("wolf", proposals.get(0).getTarget());
        assertEquals("bread", proposals.get(1).getTarget());
        assertEquals("pouch", proposals.get(1).getSecondTarget());
    }
}
