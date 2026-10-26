package engine.commands;

import java.util.Objects;

/**
 * An action someone wants to take, before the engine has checked it: the action type and up to
 * two targets (e.g. TAKE "iron key", MOVE "east", PUT "potion" into "pouch").
 *
 * <p>Proposals come from the LLM or from the views' buttons; {@link CommandFactory} turns them
 * into commands.
 */
public class ActionProposal {

    private final ActionType type;
    private final String target;
    private final String secondTarget;

    public ActionProposal(ActionType type, String target, String secondTarget) {
        this.type = Objects.requireNonNull(type, "type");
        this.target = target;
        this.secondTarget = secondTarget;
    }

    public ActionType getType() {
        return type;
    }

    /** The first target, or {@code null} if the action has none. */
    public String getTarget() {
        return target;
    }

    /** The second target, or {@code null} if the action has none. */
    public String getSecondTarget() {
        return secondTarget;
    }
}
