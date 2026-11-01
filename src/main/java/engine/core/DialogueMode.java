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
 * <p>The design's {@code questOffered} flag ({@code isQuestOffered()}, {@code setQuestOffered()}) is
 * added together with quest offers.
 */
public class DialogueMode implements GameMode {

    private static final Set<ActionType> ALLOWED = EnumSet.of(
            ActionType.LEAVE, ActionType.TAKE, ActionType.LOOK, ActionType.INVENTORY);

    private final NPC npc;

    public DialogueMode(NPC npc) {
        this.npc = Objects.requireNonNull(npc, "npc");
    }

    /** The NPC the player is talking to. */
    public NPC getNpc() {
        return npc;
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
