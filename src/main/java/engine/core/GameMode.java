package engine.core;

import engine.commands.ActionType;

/**
 * The situation the game is in, deciding which actions are allowed and whether saving is possible.
 *
 * <p>Pattern role: <b>State</b> of the State pattern, with {@link GameState} as the Context.
 */
public interface GameMode {

    boolean allows(ActionType type);

    boolean canSave();
}
