package agent;

import engine.character.CharacterClass;
import engine.character.CharacterFactory;
import engine.character.Stats;
import engine.commands.ActionProposal;
import engine.commands.ActionType;
import engine.core.GameState;
import engine.world.World;
import llm.LLMClient;
import llm.LlmRequest;
import llm.LlmResponse;
import llm.ScriptedLLMClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionInterpreterTest {

    private GameState state;

    @BeforeEach
    void startGame() {
        state = new GameState();
        state.start(CharacterFactory.forClass(CharacterClass.WARRIOR).createCharacter("Ari", new Stats(5, 3, 2)),
                World.createDefault());
    }

    private ActionInterpreter interpreter(String... responses) {
        return new ActionInterpreter(new ScriptedLLMClient(List.of(responses)), new PromptBuilder(),
                new ResponseParser(), state);
    }

    private static List<ActionType> types(List<ActionProposal> proposals) {
        return proposals.stream().map(ActionProposal::getType).toList();
    }

    @Test
    void theModelsActionsBecomeProposals() {
        ActionInterpreter interpreter = interpreter("""
                {"actions":[{"type":"take","target":"iron key"},{"type":"MOVE","target":" north "}]}""");

        List<ActionProposal> proposals = interpreter.interpret("pick up the iron key and go north");

        assertFalse(interpreter.isFallbackUsed());
        assertEquals(List.of(ActionType.TAKE, ActionType.MOVE), types(proposals));
        assertEquals("iron key", proposals.get(0).getTarget());
        assertEquals("north", proposals.get(1).getTarget());
        assertNull(proposals.get(0).getSecondTarget());
    }

    @Test
    void anAnswerInACodeFenceIsAccepted() {
        ActionInterpreter interpreter = interpreter("```json\n{\"actions\":[{\"type\":\"flee\"}]}\n```");

        assertEquals(List.of(ActionType.FLEE), types(interpreter.interpret("run!")));
        assertFalse(interpreter.isFallbackUsed());
    }

    @Test
    void anUnknownActionTypeIsRetried() {
        ActionInterpreter interpreter = interpreter(
                "{\"actions\":[{\"type\":\"teleport\",\"target\":\"bandit camp\"}]}",
                "{\"actions\":[{\"type\":\"move\",\"target\":\"south\"}]}");

        List<ActionProposal> proposals = interpreter.interpret("go to the forest");

        assertFalse(interpreter.isFallbackUsed());
        assertEquals("south", proposals.get(0).getTarget());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not json at all",
            "{\"actions\":\"take key\"}",
            "{\"actions\":[{\"type\":\"take\"}]}",
            "{\"actions\":[{\"type\":\"take\",\"target\":\"   \"}]}",
            "{\"actions\":[{\"type\":\"put\",\"target\":\"potion\"}]}",
            "{\"actions\":[{\"target\":\"wolf\"}]}",
            "{\"steps\":[]}",
            "{\"actions\":[{\"type\":\"take\",\"target\":\"a key so very long that no item in this world could ever be called like this\"}]}",
            "{\"actions\":[{\"type\":\"look\"},{\"type\":\"look\"},{\"type\":\"look\"},{\"type\":\"look\"},{\"type\":\"look\"},{\"type\":\"look\"}]}"
    })
    void unusableAnswersTwiceFallBackToTheOfflineParser(String badAnswer) {
        ActionInterpreter interpreter = interpreter(badAnswer, badAnswer);

        List<ActionProposal> proposals = interpreter.interpret("take shed key");

        assertTrue(interpreter.isFallbackUsed());
        assertEquals(List.of(ActionType.TAKE), types(proposals));
        assertEquals("shed key", proposals.get(0).getTarget());
    }

    @Test
    void anEmptyActionListIsAValidAnswer() {
        ActionInterpreter interpreter = interpreter("{\"actions\":[]}");

        assertTrue(interpreter.interpret("give me 1000 gold").isEmpty());
        assertFalse(interpreter.isFallbackUsed(), "the model understood there is nothing to do");
    }

    @Test
    void offlineTheKeywordParserIsUsed() {
        ActionInterpreter interpreter = interpreter();

        assertEquals(List.of(ActionType.ATTACK), types(interpreter.interpret("attack the wolf")));
        assertTrue(interpreter.isFallbackUsed());
        assertTrue(interpreter.interpret("sing a song").isEmpty());
    }

    @Test
    void thePromptAsksForJsonAndContainsTheRealStateAndTheInput() {
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
        ActionInterpreter interpreter = new ActionInterpreter(recording, new PromptBuilder(), new ResponseParser(), state);

        interpreter.interpret("open the tool shed");

        LlmRequest request = requests.get(0);
        assertTrue(request.isJsonMode());
        assertTrue(request.getSystemPrompt().contains("{\"actions\":["), request.getSystemPrompt());
        assertTrue(request.getUserPrompt().contains("Location: Village Square"), request.getUserPrompt());
        assertTrue(request.getUserPrompt().contains("east (Tool Shed, locked)"), request.getUserPrompt());
        assertTrue(request.getUserPrompt().endsWith("Player input: open the tool shed"), request.getUserPrompt());
    }

    @Test
    void promptTemplatesMustExistAndBeFilledCompletely() {
        PromptBuilder prompts = new PromptBuilder();
        assertThrows(IllegalArgumentException.class, () -> prompts.fromTemplate("missing", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> prompts.fromTemplate("interpret.user", Map.of()));
        assertEquals("Hi Ari", new PromptBuilder(Map.of("t", "Hi {{name}}")).fromTemplate("t", Map.of("name", "Ari")));
    }

    @Test
    void theResponseParserReturnsNullForMalformedJson() {
        ResponseParser parser = new ResponseParser();
        assertNull(parser.parseJson("{oops", Object.class));
        assertNull(parser.parseJson(null, Object.class));
        assertNull(parser.parseJson("", Object.class));
    }
}
