package agent;

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
 * {@code {"actions":[{"type":..,"target":..,"secondTarget":..}]}}. {@link #parse} rejects malformed
 * JSON and unknown action types; {@link #isValid} rejects answers the command factory could not
 * build (a missing target) or that are implausible (too many actions, very long targets), so such
 * output never reaches {@code CommandFactory}. The fallback is {@link OfflineFallbackParser}. Whether
 * a valid proposal may actually happen is still decided by the rules engine.
 */
public class ActionInterpreter extends LlmTask<String, List<ActionProposal>> {

    private static final int MAX_TOKENS = 300;
    private static final int MAX_ACTIONS = 5;
    private static final int MAX_TARGET_LENGTH = 60;
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

    /** {@code null} if the JSON is malformed or names an action type the game does not have. */
    @Override
    protected List<ActionProposal> parse(LlmResponse response) {
        ActionsJson json = parser.parseJson(response.getText(), ActionsJson.class);
        if (json == null || json.actions == null) {
            return null;
        }
        List<ActionProposal> proposals = new ArrayList<>();
        for (ActionJson action : json.actions) {
            ActionType type = action == null ? null : actionType(action.type);
            if (type == null) {
                return null;
            }
            proposals.add(new ActionProposal(type, clean(action.target), clean(action.secondTarget)));
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
        if (output.size() > MAX_ACTIONS) {
            return false;
        }
        for (ActionProposal proposal : output) {
            if (NEED_TARGET.contains(proposal.getType()) && proposal.getTarget() == null) {
                return false;
            }
            if (proposal.getType() == ActionType.PUT && proposal.getSecondTarget() == null) {
                return false;
            }
            if (tooLong(proposal.getTarget()) || tooLong(proposal.getSecondTarget())) {
                return false;
            }
        }
        return true;
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

    /** The JSON shape the model answers with. */
    private static final class ActionsJson {
        List<ActionJson> actions;
    }

    private static final class ActionJson {
        String type;
        String target;
        String secondTarget;
    }
}
