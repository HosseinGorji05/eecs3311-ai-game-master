package engine.commands;

import engine.core.DialogueMode;
import engine.core.ExplorationMode;
import engine.core.GameState;
import engine.core.RulesEngine;
import engine.events.EventType;
import engine.events.GameEvent;
import engine.world.NPC;

/**
 * Ends the current conversation and returns to {@link ExplorationMode} (SD12, UC03 step 7).
 *
 * <p>Pattern role: <b>ConcreteCommand</b>. Only dialogue mode allows LEAVE. A successful leave adds
 * a DIALOGUE event "left the conversation with ..." (subject = the NPC's id).
 */
public class LeaveCommand implements GameCommand {

    @Override
    public ActionType getType() {
        return ActionType.LEAVE;
    }

    /** Invalid outside a conversation (the mode check normally rejects LEAVE before this). */
    @Override
    public ValidationResult validate(GameState state) {
        if (!(state.getMode() instanceof DialogueMode)) {
            return new ValidationResult(false, "You are not talking to anyone.");
        }
        return new ValidationResult(true, null);
    }

    @Override
    public ActionResult execute(GameState state, RulesEngine rules) {
        if (!(state.getMode() instanceof DialogueMode dialogue)) {
            throw new IllegalStateException("validate() must succeed before execute()");
        }
        NPC npc = dialogue.getNpc();
        state.setMode(new ExplorationMode());
        ActionResult result = new ActionResult(true, "You leave the conversation with " + npc.getName() + ".");
        result.addEvent(new GameEvent(EventType.DIALOGUE, state.getTurn(),
                "left the conversation with " + npc.getName(), npc.getId(), 0));
        return result;
    }
}
