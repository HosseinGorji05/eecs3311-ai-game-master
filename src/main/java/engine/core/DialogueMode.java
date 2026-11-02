package engine.core;

import engine.commands.ActionType;
import engine.world.NPC;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * The mode while the player talks to one NPC: only LEAVE, TAKE (an item the NPC hands over), LOOK
 * and INVENTORY are allowed, and the game can be saved. {@code TalkCommand} enters it and
 * {@code LeaveCommand} returns to {@link ExplorationMode}.
 *
 * <p>Pattern role: <b>ConcreteState</b> of the State pattern.
 *
 * <p>The mode also remembers whether the NPC has offered a quest in this conversation, so it offers
 * at most one (SD07 n2), whether the player accepts or declines it.
 */
public class DialogueMode implements GameMode {

    private static final Set<ActionType> ALLOWED = EnumSet.of(
            ActionType.LEAVE, ActionType.TAKE, ActionType.LOOK, ActionType.INVENTORY);

    private final NPC npc;
    private boolean questOffered;

    public DialogueMode(NPC npc) {
        this.npc = Objects.requireNonNull(npc, "npc");
    }

    /** The NPC the player is talking to. */
    public NPC getNpc() {
        return npc;
    }

    /** Whether the NPC has already offered a quest in this conversation. */
    public boolean isQuestOffered() {
        return questOffered;
    }

    /** Records that the NPC has offered a quest; a new conversation starts without the mark. */
    public void setQuestOffered() {
        questOffered = true;
    }

    @Override
    public boolean allows(ActionType type) {
        Objects.requireNonNull(type, "type");
        return ALLOWED.contains(type);
    }

    /** Saving is refused only in combat (F10). */
    @Override
    public boolean canSave() {
        return true;
    }
}
