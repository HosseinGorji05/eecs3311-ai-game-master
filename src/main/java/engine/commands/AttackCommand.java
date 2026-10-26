package engine.commands;

import engine.character.Enemy;
import engine.character.Player;
import engine.core.CombatMode;
import engine.core.ExplorationMode;
import engine.core.GameState;
import engine.core.RulesEngine;
import engine.events.EventType;
import engine.events.EventVerb;
import engine.events.GameEvent;
import engine.world.Location;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One round of combat against an enemy in the current location (SD04).
 *
 * <p>Pattern role: <b>ConcreteCommand</b>. The first attack switches the game to a new
 * {@link CombatMode}. The player attacks through {@link RulesEngine#resolveAttack}; a defeated
 * enemy is removed and {@link ExplorationMode} is restored, otherwise the enemy attacks back.
 *
 * <p>Every attack is recorded as a COMBAT event. A defeat adds a COMBAT event with verb DEFEATED
 * and the enemy's name as subject (so DEFEAT objectives match); the player's death adds one with
 * verb DIED.
 */
public class AttackCommand implements GameCommand {

    private final String targetName;

    public AttackCommand(String targetName) {
        Objects.requireNonNull(targetName, "targetName");
        if (targetName.isBlank()) {
            throw new IllegalArgumentException("Target name must not be blank");
        }
        this.targetName = targetName.trim();
    }

    @Override
    public ActionType getType() {
        return ActionType.ATTACK;
    }

    /** Invalid if the enemy is not here, or the player is already fighting a different enemy. */
    @Override
    public ValidationResult validate(GameState state) {
        Enemy enemy = state.currentLocation().findEnemy(targetName);
        if (enemy == null) {
            return new ValidationResult(false, "There is no " + targetName + " here.");
        }
        if (state.getMode() instanceof CombatMode combat && combat.getEnemy() != enemy) {
            return new ValidationResult(false, "You are fighting the " + combat.getEnemy().getName() + ".");
        }
        return new ValidationResult(true, null);
    }

    @Override
    public ActionResult execute(GameState state, RulesEngine rules) {
        Location location = state.currentLocation();
        Enemy enemy = location.findEnemy(targetName);
        if (enemy == null) {
            throw new IllegalStateException("validate() must succeed before execute()");
        }
        Player player = state.getPlayer();
        if (!(state.getMode() instanceof CombatMode)) {
            state.setMode(new CombatMode(enemy));
        }
        List<String> messages = new ArrayList<>();
        List<GameEvent> events = new ArrayList<>();
        ActionResult playerAttack = rules.resolveAttack(player, enemy);
        messages.add(playerAttack.getMessage());
        events.add(combatEvent(state, playerAttack.getMessage(), enemy.getName(), null));
        if (!enemy.isAlive()) {
            location.removeEnemy(enemy);
            state.setMode(new ExplorationMode());
            messages.add("The " + enemy.getName() + " is defeated.");
            events.add(combatEvent(state, enemy.getName() + " defeated", enemy.getName(), EventVerb.DEFEATED));
        } else {
            enemyTurn(enemy, state, rules, messages, events);
        }
        return result(true, messages, events);
    }

    /**
     * The enemy attacks the player; if the player dies, a COMBAT event with verb DIED is added
     * (the game-over check belongs to the controller).
     */
    static void enemyTurn(Enemy enemy, GameState state, RulesEngine rules, List<String> messages,
                          List<GameEvent> events) {
        Player player = state.getPlayer();
        ActionResult enemyAttack = rules.resolveAttack(enemy, player);
        messages.add(enemyAttack.getMessage());
        events.add(combatEvent(state, enemyAttack.getMessage(), player.getName(), null));
        if (!player.isAlive()) {
            messages.add("You die.");
            events.add(combatEvent(state, player.getName() + " died", player.getName(), EventVerb.DIED));
        }
    }

    static GameEvent combatEvent(GameState state, String description, String subjectId, EventVerb verb) {
        return new GameEvent(EventType.COMBAT, state.getTurn(), description, subjectId, 0, verb);
    }

    static ActionResult result(boolean success, List<String> messages, List<GameEvent> events) {
        ActionResult result = new ActionResult(success, String.join("\n", messages));
        events.forEach(result::addEvent);
        return result;
    }
}
