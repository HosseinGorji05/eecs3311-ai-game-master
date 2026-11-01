package engine.commands;

import engine.core.GameState;
import engine.core.RulesEngine;

/**
 * Describes the current location again: its description, items, enemies and exits.
 *
 * <p>Pattern role: <b>ConcreteCommand</b>. Read-only: it is always valid, changes nothing and adds
 * no events (the turn still advances, as for every command, SD03).
 */
public class LookCommand implements GameCommand {

    @Override
    public ActionType getType() {
        return ActionType.LOOK;
    }

    @Override
    public ValidationResult validate(GameState state) {
        return new ValidationResult(true, null);
    }

    @Override
    public ActionResult execute(GameState state, RulesEngine rules) {
        return new ActionResult(true, state.currentLocation().describe());
    }
}
