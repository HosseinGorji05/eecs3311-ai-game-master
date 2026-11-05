package presentation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The lines of the conversation in the dialogue panel (F03): what the player said and what the NPC replied
 * ({@code NpcReply.getText()}), in this conversation only. It follows the game's {@link DialogueState}: a new
 * conversation (another {@code DialogueMode}) starts with a fresh opening line, and leaving clears it. The
 * engine does not save conversation lines, so a conversation continued from a save opens with a note
 * instead; the earlier exchanges are in the event log and the journal.
 *
 * <p>Used on the Swing thread only.
 */
final class ConversationLog {

    static final String CONTINUED = "(Continued from a save. Earlier exchanges are in the Event log and the Journal.)";

    private Object conversation;
    private final List<String> lines = new ArrayList<>();

    /**
     * Follows the game into, out of or between conversations.
     *
     * @param loaded whether the state comes from a loaded save
     * @return whether the lines changed
     */
    boolean follow(DialogueState state, boolean loaded) {
        Objects.requireNonNull(state, "state");
        if (!state.talking()) {
            boolean changed = conversation != null || !lines.isEmpty();
            conversation = null;
            lines.clear();
            return changed;
        }
        if (state.conversation() == conversation) {
            return false;
        }
        conversation = state.conversation();
        lines.clear();
        lines.add(loaded ? CONTINUED : "You are talking to " + state.npcName() + " (" + state.npcRole() + ").");
        return true;
    }

    void addPlayerLine(String text) {
        lines.add("You: " + text);
    }

    void addNpcLine(String npcName, String text) {
        lines.add(npcName + ": " + text);
    }

    /** Whether a conversation is being followed. */
    boolean isActive() {
        return conversation != null;
    }

    List<String> getLines() {
        return List.copyOf(lines);
    }

    /** The lines, one per row, for the panel's text area. */
    String text() {
        return String.join("\n", lines);
    }
}
