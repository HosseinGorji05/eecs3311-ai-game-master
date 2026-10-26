package engine.core;

import engine.commands.ActionResult;
import engine.commands.GameCommand;
import engine.commands.ValidationResult;
import engine.events.EventType;
import engine.events.GameEvent;
import engine.events.GameEventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Runs the commands of one turn against the real game state: "the LLM proposes, the engine
 * decides".
 *
 * <p>Pattern role: <b>Invoker</b> of the Command pattern. For each command it asks the current
 * mode whether the action type is allowed, then calls {@code validate()}, and only on a valid
 * result {@code execute()}; anything else is rejected without touching the state. The events of
 * each result are then published to the {@link GameEventBus} (SD03); a failed result that carries
 * no event of its own gets a REJECTED event, so the history also shows what the player attempted.
 *
 * <p>The dice of the design, {@code resolveAttack()} and {@code skillCheck()} are added together
 * with combat.
 */
public class RulesEngine {

    /** Message for an action the current mode does not allow (SD03). */
    static final String NOT_ALLOWED = "That is not possible right now.";

    private final GameEventBus eventBus;

    public RulesEngine(GameEventBus eventBus) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
    }

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
            ActionResult result;
            if (!state.allows(command.getType())) {
                result = reject(command, NOT_ALLOWED);
            } else {
                ValidationResult validation = command.validate(state);
                result = validation.isValid()
                        ? command.execute(state, this)
                        : reject(command, validation.getReason());
            }
            if (!result.isSuccess() && result.getEvents().isEmpty()) {
                result.addEvent(new GameEvent(EventType.REJECTED, state.getTurn(),
                        "Rejected " + command.getType() + ": " + result.getMessage(), command.getType().name(), 0));
            }
            for (GameEvent event : result.getEvents()) {
                eventBus.publish(event);
            }
            results.add(result);
        }
        state.nextTurn();
        return results;
    }

    private ActionResult reject(GameCommand command, String reason) {
        return new ActionResult(false, reason);
    }
}
