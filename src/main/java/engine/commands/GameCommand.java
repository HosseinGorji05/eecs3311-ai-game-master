package engine.commands;

import engine.core.GameState;
import engine.core.RulesEngine;

/**
 * One player action as an object.
 *
 * <p>Pattern role: <b>Command</b> of the Command pattern, with {@link RulesEngine} as the Invoker,
 * {@link CommandFactory} as the Client and {@link GameState} (and what it contains) as the
 * Receiver. {@link #validate} only reads the state; {@link #execute} is called only after a valid
 * result, so a rejected command never changes the game.
 */
public interface GameCommand {

    ActionType getType();

    /** Checks the command against the real game state without changing it. */
    ValidationResult validate(GameState state);

    /**
     * Carries out the command.
     *
     * @throws IllegalStateException if {@link #validate} has not returned a valid result first
     */
    ActionResult execute(GameState state, RulesEngine rules);
}
