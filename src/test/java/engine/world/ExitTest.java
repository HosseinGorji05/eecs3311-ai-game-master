package engine.world;

import engine.items.KeyItem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExitTest {

    private static final Location SHED = new Location("tool-shed", "Tool Shed", "A cramped shed.");

    @Test
    void exitWithoutAKeyNameIsOpen() {
        Exit exit = new Exit("square-east", Direction.EAST, SHED);

        assertFalse(exit.isLocked());
        assertNull(exit.getRequiredKeyName());
        assertEquals("square-east", exit.getId());
        assertEquals(Direction.EAST, exit.getDirection());
        assertSame(SHED, exit.getDestination());
        assertTrue(exit.unlock(null), "an open exit stays open");
    }

    @Test
    void exitWithAKeyNameStartsLocked() {
        Exit exit = new Exit("square-east", Direction.EAST, SHED, "shed key");

        assertTrue(exit.isLocked());
        assertEquals("shed key", exit.getRequiredKeyName());
    }

    @Test
    void theRightKeyUnlocksTheExitForGoodIgnoringCase() {
        Exit exit = new Exit("square-east", Direction.EAST, SHED, "shed key");

        assertTrue(exit.unlock(new KeyItem("Shed Key", 0.1)));

        assertFalse(exit.isLocked());
        assertTrue(exit.unlock(null));
        assertFalse(exit.isLocked());
    }

    @Test
    void aWrongKeyOrNoKeyLeavesTheExitLocked() {
        Exit exit = new Exit("square-east", Direction.EAST, SHED, "shed key");

        assertFalse(exit.unlock(new KeyItem("iron key", 0.2)));
        assertFalse(exit.unlock(null));

        assertTrue(exit.isLocked());
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertThrows(NullPointerException.class, () -> new Exit(null, Direction.EAST, SHED));
        assertThrows(IllegalArgumentException.class, () -> new Exit(" ", Direction.EAST, SHED));
        assertThrows(NullPointerException.class, () -> new Exit("id", null, SHED));
        assertThrows(NullPointerException.class, () -> new Exit("id", Direction.EAST, null));
        assertThrows(IllegalArgumentException.class, () -> new Exit("id", Direction.EAST, SHED, " "));
    }
}
