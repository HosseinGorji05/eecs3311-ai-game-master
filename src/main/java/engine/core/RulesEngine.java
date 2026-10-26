package engine.core;

import engine.commands.ActionResult;
import engine.commands.GameCommand;
import engine.commands.ValidationResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Runs the commands of one turn against the real game state: "the LLM proposes, the engine
 * decides".
 *
 * <p>Pattern role: <b>Invoker</b> of the Command pattern. For each command it asks the current
 * mode whether the action type is allowed, then calls {@code validate()}, and only on a valid
 * result {@code execute()}; anything else is rejected without touching the state (SD03).
 *
 * <p>The dice and event bus of the design, {@code resolveAttack()} and {@code skillCheck()} are
 * added together with combat and events; rejected commands will then also publish a REJECTED event.
 */
public class RulesEngine {

    /** Message for an action the current mode does not allow (SD03). */
    static final String NOT_ALLOWED = "That is not possible right now.";

    /**
     * Runs the commands in order and then advances the turn once.
     *
     * @return one result per command, in the same order
     */
    public List<ActionResult> executeAll(List<GameCommand> commands, GameState state) {
        Objects.requireNonNull(commands, "commands");
        Objects.requireNonNull(state, "state");
        List<ActionResult> results = new ArrayList<>();
        for (GameCommand command : commands) {
            if (!state.allows(command.getType())) {
                results.add(reject(command, NOT_ALLOWED));
                continue;
            }
            ValidationResult validation = command.validate(state);
            if (validation.isValid()) {
                results.add(command.execute(state, this));
            } else {
                results.add(reject(command, validation.getReason()));
            }
        }
        state.nextTurn();
        return results;
    }

    private ActionResult reject(GameCommand command, String reason) {
        return new ActionResult(false, reason);
    }
}
