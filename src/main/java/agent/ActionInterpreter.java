package agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import engine.commands.ActionProposal;
import engine.commands.ActionType;
import engine.core.GameState;
import llm.LLMClient;
import llm.LlmRequest;
import llm.LlmResponse;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns the player's free text into action proposals with the language model (F02), e.g.
 * "pick up the iron key and go north" into TAKE "iron key" and MOVE "north".
 *
 * <p>Pattern role: <b>ConcreteClass</b> of the Template Method pattern. The model must answer
 * {@code {"actions":[{"type":..,"target":..,"secondTarget":..}]}}; {@link #parse} also accepts
 * common variations of that shape and rejects malformed JSON and unknown action types;
 * {@link #isValid} rejects answers the command factory could not build (a missing target) or that
 * are implausible (too many actions, very long targets), so such output never reaches
 * {@code CommandFactory}. The fallback is {@link OfflineFallbackParser}. Whether
 * a valid proposal may actually happen is still decided by the rules engine.
 */
public class ActionInterpreter extends LlmTask<String, List<ActionProposal>> {

    private static final int MAX_TOKENS = 300;
    private static final int MAX_ACTIONS = 5;
    private static final int MAX_TARGET_LENGTH = 60;
    private static final Set<String> TYPE_FIELDS = Set.of("type", "action", "actiontype");
    private static final Set<String> TARGET_FIELDS = Set.of("target", "object");
    private static final Set<String> SECOND_TARGET_FIELDS = Set.of("secondtarget", "container");
    private static final Set<ActionType> NEED_TARGET = EnumSet.of(ActionType.MOVE, ActionType.TAKE,
            ActionType.DROP, ActionType.PUT, ActionType.USE, ActionType.EQUIP, ActionType.TALK, ActionType.ATTACK);

    private final OfflineFallbackParser offlineParser = new OfflineFallbackParser();

    public ActionInterpreter(LLMClient llm, PromptBuilder prompts, ResponseParser parser, GameState state) {
        super(llm, prompts, parser, state);
    }

    /** The actions in the text; empty if it was not understood. */
    public List<ActionProposal> interpret(String text) {
        return run(text);
    }

    @Override
    protected LlmRequest buildPrompt(String input) {
        String system = prompts.fromTemplate("interpret.system", Map.of());
        String user = prompts.fromTemplate("interpret.user",
                Map.of("state", prompts.stateSummary(state), "input", input));
        return new LlmRequest(system, user, true, MAX_TOKENS);
    }

    /**
     * {@code null} if the reply holds no usable JSON or names an action type the game does not
     * have. Accepted shapes: {@code {"actions":[...]}}, a bare array of actions, or a single action
     * object. Field names are matched ignoring case and underscores ({@code type}/{@code action},
     * {@code target}/{@code object}, {@code secondTarget}/{@code container}); action types ignore case.
     */
    @Override
    protected List<ActionProposal> parse(LlmResponse response) {
        JsonArray actions = actions(parser.parseJson(response.getText(), JsonElement.class));
        if (actions == null) {
            return null;
        }
        List<ActionProposal> proposals = new ArrayList<>();
        for (JsonElement element : actions) {
            if (!element.isJsonObject()) {
                return null;
            }
            JsonObject action = element.getAsJsonObject();
            ActionType type = actionType(field(action, TYPE_FIELDS));
            if (type == null) {
                return null;
            }
            proposals.add(new ActionProposal(type, clean(field(action, TARGET_FIELDS)),
                    clean(field(action, SECOND_TARGET_FIELDS))));
        }
        return List.copyOf(proposals);
    }

    /**
     * Valid if there are at most {@code MAX_ACTIONS} actions, every action that needs a target has
     * one (PUT also a container), and no target is longer than {@code MAX_TARGET_LENGTH}. An empty
     * list is valid: the model understood that there is nothing to do.
     */
    @Override
    protected boolean isValid(List<ActionProposal> output, String input) {
        return problem(output) == null;
    }

    @Override
    protected String describeInvalid(List<ActionProposal> output, String input) {
        String problem = problem(output);
        return problem == null ? super.describeInvalid(output, input) : problem;
    }

    /** The first rule the proposals break, or {@code null} if they break none. */
    private static String problem(List<ActionProposal> output) {
        if (output.size() > MAX_ACTIONS) {
            return "too many actions (" + output.size() + ", at most " + MAX_ACTIONS + ")";
        }
        for (ActionProposal proposal : output) {
            String type = proposal.getType().name().toLowerCase(Locale.ROOT);
            if (NEED_TARGET.contains(proposal.getType()) && proposal.getTarget() == null) {
                return type + " needs a target";
            }
            if (proposal.getType() == ActionType.PUT && proposal.getSecondTarget() == null) {
                return "put needs a container (secondTarget)";
            }
            if (tooLong(proposal.getTarget()) || tooLong(proposal.getSecondTarget())) {
                return type + " has a target longer than " + MAX_TARGET_LENGTH + " characters";
            }
        }
        return null;
    }

    @Override
    protected List<ActionProposal> fallback(String input) {
        return offlineParser.parse(input);
    }

    private static ActionType actionType(String name) {
        if (name == null) {
            return null;
        }
        try {
            return ActionType.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknownType) {
            return null;
        }
    }

    private static String clean(String target) {
        return target == null || target.isBlank() ? null : target.trim();
    }

    private static boolean tooLong(String target) {
        return target != null && target.length() > MAX_TARGET_LENGTH;
    }

    /** The action list of the reply: its "actions" array, the reply itself if it is an array, or a single action. */
    private static JsonArray actions(JsonElement root) {
        if (root == null) {
            return null;
        }
        if (root.isJsonArray()) {
            return root.getAsJsonArray();
        }
        if (!root.isJsonObject()) {
            return null;
        }
        JsonObject object = root.getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (normalize(entry.getKey()).equals("actions")) {
                return entry.getValue().isJsonArray() ? entry.getValue().getAsJsonArray() : null;
            }
        }
        if (field(object, TYPE_FIELDS) != null) {
            JsonArray single = new JsonArray();
            single.add(object);
            return single;
        }
        return null;
    }

    /** The text of the first field whose normalized name is one of {@code names}; {@code null} if absent, null or not text. */
    private static String field(JsonObject object, Set<String> names) {
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (names.contains(normalize(entry.getKey()))) {
                JsonElement value = entry.getValue();
                return value.isJsonPrimitive() ? value.getAsString() : null;
            }
        }
        return null;
    }

    private static String normalize(String fieldName) {
        return fieldName.replace("_", "").replace("-", "").trim().toLowerCase(Locale.ROOT);
    }
}
