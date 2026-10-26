package engine.commands;

import engine.character.Enemy;
import engine.character.StatType;
import engine.core.CombatMode;
import engine.core.ExplorationMode;
import engine.core.GameState;
import engine.core.RulesEngine;
import engine.events.EventVerb;
import engine.events.GameEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Tries to escape from the current fight with a skill check: d20 + agility against
 * {@code DIFFICULTY} (SD12).
 *
 * <p>Pattern role: <b>ConcreteCommand</b>. On success the game returns to
 * {@link ExplorationMode} (the player stays where they are, and so does the enemy); on failure the
 * enemy takes its turn. Both outcomes are COMBAT events ("fled from ..." with verb FLED, or
 * "failed to flee from ..."), and the result succeeds only if the player escaped.
 */
public class FleeCommand implements GameCommand {

    private static final int DIFFICULTY = 12;

    @Override
    public ActionType getType() {
        return ActionType.FLEE;
    }

    /** Invalid outside combat (the mode check normally rejects FLEE before this). */
    @Override
    public ValidationResult validate(GameState state) {
        if (!(state.getMode() instanceof CombatMode)) {
            return new ValidationResult(false, "You are not in a fight.");
        }
        return new ValidationResult(true, null);
    }

    @Override
    public ActionResult execute(GameState state, RulesEngine rules) {
        if (!(state.getMode() instanceof CombatMode combat)) {
            throw new IllegalStateException("validate() must succeed before execute()");
        }
        Enemy enemy = combat.getEnemy();
        int agility = state.getPlayer().getStats().modifier(StatType.AGILITY);
        boolean escaped = rules.skillCheck(agility, DIFFICULTY);
        List<String> messages = new ArrayList<>();
        List<GameEvent> events = new ArrayList<>();
        if (escaped) {
            state.setMode(new ExplorationMode());
            messages.add("You escape from the " + enemy.getName() + ".");
            events.add(AttackCommand.combatEvent(state, "fled from the " + enemy.getName(), enemy.getName(),
                    EventVerb.FLED));
        } else {
            messages.add("You fail to escape from the " + enemy.getName() + ".");
            events.add(AttackCommand.combatEvent(state, "failed to flee from the " + enemy.getName(),
                    enemy.getName(), null));
            AttackCommand.enemyTurn(enemy, state, rules, messages, events);
        }
        return AttackCommand.result(escaped, messages, events);
    }
}
