package engine.core;

import engine.character.Combatant;
import engine.commands.ActionResult;
import engine.commands.GameCommand;
import engine.commands.ValidationResult;
import engine.events.EventType;
import engine.events.GameEvent;
import engine.events.GameEventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 * <p>Combat and skill checks are decided here by the {@link DiceRoller} and the combatants'
 * stats, never by the LLM (SD04, SD12).
 */
public class RulesEngine {

    /** Message for an action the current mode does not allow (SD03). */
    static final String NOT_ALLOWED = "That is not possible right now.";

    private final DiceRoller dice;
    private final GameEventBus eventBus;

    public RulesEngine(DiceRoller dice, GameEventBus eventBus) {
        this.dice = Objects.requireNonNull(dice, "dice");
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

    /**
     * One attack: rolls a d20, adds the attacker's attack bonus and hits if the total reaches the
     * target's defense; a hit applies the attacker's damage to the target.
     *
     * @return a result that succeeds on a hit, with a message showing the roll; it carries no
     *         events, the calling command records them with the current turn
     */
    public ActionResult resolveAttack(Combatant attacker, Combatant target) {
        Objects.requireNonNull(attacker, "attacker");
        Objects.requireNonNull(target, "target");
        int roll = dice.rollD20();
        int bonus = attacker.attackBonus();
        int defense = target.defense();
        String check = String.format(Locale.ROOT, "%s attacks %s: rolls %d + %d = %d against defense %d.",
                attacker.getName(), target.getName(), roll, bonus, roll + bonus, defense);
        if (roll + bonus < defense) {
            return new ActionResult(false, check + " Miss.");
        }
        int damage = attacker.damage();
        target.takeDamage(damage);
        return new ActionResult(true, String.format(Locale.ROOT, "%s Hit for %d damage (%s: %d/%d HP).",
                check, damage, target.getName(), target.getHp(), target.getMaxHp()));
    }

    /** Whether a d20 roll plus {@code statValue} reaches {@code difficulty}. */
    public boolean skillCheck(int statValue, int difficulty) {
        return dice.rollD20() + statValue >= difficulty;
    }

    private ActionResult reject(GameCommand command, String reason) {
        return new ActionResult(false, reason);
    }
}
