package engine.commands;

import engine.core.DialogueMode;
import engine.core.GameState;
import engine.core.RulesEngine;
import engine.events.EventType;
import engine.events.GameEvent;
import engine.world.NPC;

import java.util.Objects;

/**
 * Starts a conversation with an NPC in the current location by switching to a new
 * {@link DialogueMode} (F03, UC03).
 *
 * <p>Pattern role: <b>ConcreteCommand</b>. The NPC is found by name or role ("Brann", "the
 * blacksmith"). Exploration is the only mode that allows TALK, so it is rejected in combat and while
 * already talking. A successful talk adds a DIALOGUE event (subject = the NPC's id).
 */
public class TalkCommand implements GameCommand {

    private final String npcName;

    public TalkCommand(String npcName) {
        Objects.requireNonNull(npcName, "npcName");
        if (npcName.isBlank()) {
            throw new IllegalArgumentException("NPC name must not be blank");
        }
        this.npcName = npcName.trim();
    }

    @Override
    public ActionType getType() {
        return ActionType.TALK;
    }

    /** Invalid if the NPC is not here or the player is already in a conversation. */
    @Override
    public ValidationResult validate(GameState state) {
        if (state.getMode() instanceof DialogueMode dialogue) {
            return new ValidationResult(false, "You are already talking to " + dialogue.getNpc().getName() + ".");
        }
        if (state.currentLocation().findNpc(npcName) == null) {
            return new ValidationResult(false, "There is no " + npcName + " here.");
        }
        return new ValidationResult(true, null);
    }

    @Override
    public ActionResult execute(GameState state, RulesEngine rules) {
        NPC npc = state.currentLocation().findNpc(npcName);
        if (npc == null) {
            throw new IllegalStateException("validate() must succeed before execute()");
        }
        state.setMode(new DialogueMode(npc));
        ActionResult result = new ActionResult(true, "You start talking to " + npc.getName() + ", the "
                + npc.getRole() + ".");
        result.addEvent(new GameEvent(EventType.DIALOGUE, state.getTurn(), "started talking to " + npc.getName(),
                npc.getId(), 0));
        return result;
    }
}
