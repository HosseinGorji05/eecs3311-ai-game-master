package presentation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static presentation.TypedInput.Route.ACCEPT;
import static presentation.TypedInput.Route.COMMAND;
import static presentation.TypedInput.Route.DECLINE;
import static presentation.TypedInput.Route.EMPTY;
import static presentation.TypedInput.Route.FLEE;
import static presentation.TypedInput.Route.HELP;
import static presentation.TypedInput.Route.LEAVE;
import static presentation.TypedInput.Route.SAY;

/** Where a line typed into the GUI goes: the same places the CLI sends it. */
class TypedInputTest {

    @Test
    void outsideAConversationTextIsANaturalLanguageCommand() {
        assertEquals(COMMAND, TypedInput.route("take the shed key and go south", false));
        assertEquals(COMMAND, TypedInput.route("bye", false), "bye only leaves in a conversation, as in the CLI");
        assertEquals(COMMAND, TypedInput.route("flee now", false), "only the single word is the fixed action");
    }

    @Test
    void inAConversationTextIsSaidToTheNpc() {
        assertEquals(SAY, TypedInput.route("Have you seen the bandits?", true));
        assertEquals(SAY, TypedInput.route("leave me alone", true));
    }

    @Test
    void theFixedWordsAreActionsInAnyCaseAndSpacing() {
        assertEquals(LEAVE, TypedInput.route("  Leave ", false));
        assertEquals(LEAVE, TypedInput.route("leave", true));
        assertEquals(LEAVE, TypedInput.route("BYE", true));
        assertEquals(LEAVE, TypedInput.route("goodbye", true));
        assertEquals(FLEE, TypedInput.route("flee", false));
        assertEquals(FLEE, TypedInput.route("flee", true));
        assertEquals(ACCEPT, TypedInput.route("accept", true));
        assertEquals(DECLINE, TypedInput.route("Decline", false));
        assertEquals(HELP, TypedInput.route("help", true));
    }

    @Test
    void blankInputGoesNowhere() {
        assertEquals(EMPTY, TypedInput.route("   ", false));
        assertEquals(EMPTY, TypedInput.route(null, true));
    }
}
