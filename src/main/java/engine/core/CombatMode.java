package engine.core;

import engine.character.Enemy;
import engine.commands.ActionType;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * The mode while the player fights one enemy: only ATTACK, FLEE, USE, EQUIP, LOOK and INVENTORY
 * are allowed, and the game cannot be saved. Defeating the enemy or fleeing returns to
 * {@link ExplorationMode}.
 *
 * <p>Pattern role: <b>ConcreteState</b> of the State pattern.
 */
public class CombatMode implements GameMode {

    private static final Set<ActionType> ALLOWED = EnumSet.of(
            ActionType.ATTACK, ActionType.FLEE, ActionType.USE, ActionType.EQUIP, ActionType.LOOK, ActionType.INVENTORY);

    private final Enemy enemy;

    public CombatMode(Enemy enemy) {
        this.enemy = Objects.requireNonNull(enemy, "enemy");
    }

    /** The enemy the player is fighting. */
    public Enemy getEnemy() {
        return enemy;
    }

    @Override
    public boolean allows(ActionType type) {
        Objects.requireNonNull(type, "type");
        return ALLOWED.contains(type);
    }

    @Override
    public boolean canSave() {
        return false;
    }
}
