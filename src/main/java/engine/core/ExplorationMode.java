package engine.core;

import engine.commands.ActionType;

import java.util.Objects;

/**
 * The normal mode while the player walks around: every action except FLEE and LEAVE is allowed,
 * and the game can be saved.
 *
 * <p>Pattern role: <b>ConcreteState</b> of the State pattern.
 */
public class ExplorationMode implements GameMode {

    public ExplorationMode() {
    }

    @Override
    public boolean allows(ActionType type) {
        Objects.requireNonNull(type, "type");
        return type != ActionType.FLEE && type != ActionType.LEAVE;
    }

    @Override
    public boolean canSave() {
        return true;
    }
}
