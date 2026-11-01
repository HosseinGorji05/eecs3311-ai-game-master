package agent;

import engine.commands.ActionProposal;

import java.util.Objects;

/**
 * What an NPC says, and the one game action it proposes, if any (F03). The text is only shown;
 * only the side effect can change the game, and only after the rules engine accepts it (SD07).
 */
public class NpcReply {

    private final String text;
    private final ActionProposal sideEffect;

    /**
     * @param sideEffect the proposed action, or {@code null} for none
     * @throws IllegalArgumentException if the text is blank
     */
    public NpcReply(String text, ActionProposal sideEffect) {
        Objects.requireNonNull(text, "text");
        if (text.isBlank()) {
            throw new IllegalArgumentException("An NPC reply needs text");
        }
        this.text = text;
        this.sideEffect = sideEffect;
    }

    public String getText() {
        return text;
    }

    /** The proposed action, or {@code null} if the NPC proposes none. */
    public ActionProposal getSideEffect() {
        return sideEffect;
    }
}
