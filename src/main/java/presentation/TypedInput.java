package presentation;

import java.util.Locale;
import java.util.Set;

/**
 * Where a line typed into the GUI's input field goes, the same way {@code CliGameView.dispatch} sends it
 * (SD02 n1, SD12 n1): the single words {@code flee} and {@code leave} (and in a conversation {@code bye}
 * and {@code goodbye}) are fixed actions through {@code performAction}, so "leave" never reaches the NPC
 * agent; {@code accept} and {@code decline} answer the quest offer; {@code help} shows the GUI's help; in
 * a conversation any other line is said to the NPC ({@code sendDialogue}), otherwise it is a
 * natural-language command ({@code submitCommand}).
 *
 * <p>The CLI's other words (map, quests, save, load, saves, new, quit) are the GUI's panels and menu;
 * typed, they go to the interpreter like any text. {@code journal} and {@code history} come with their
 * panels.
 */
final class TypedInput {

    /** What to do with a typed line. */
    enum Route {
        /** Nothing typed. */
        EMPTY,
        HELP,
        LEAVE,
        FLEE,
        ACCEPT,
        DECLINE,
        /** Said to the NPC in the conversation. */
        SAY,
        /** A natural-language command. */
        COMMAND
    }

    private static final Set<String> LEAVE_WORDS = Set.of("leave", "bye", "goodbye");

    private TypedInput() {
    }

    static Route route(String line, boolean talking) {
        String text = line == null ? "" : line.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return Route.EMPTY;
        }
        if (text.split("\\s+").length == 1) {
            switch (text) {
                case "help":
                    return Route.HELP;
                case "flee":
                    return Route.FLEE;
                case "leave":
                    return Route.LEAVE;
                case "accept":
                    return Route.ACCEPT;
                case "decline":
                    return Route.DECLINE;
                default:
                    if (talking && LEAVE_WORDS.contains(text)) {
                        return Route.LEAVE;
                    }
            }
        }
        return talking ? Route.SAY : Route.COMMAND;
    }
}
