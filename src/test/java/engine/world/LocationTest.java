package engine.world;

import engine.items.Consumable;
import engine.items.KeyItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocationTest {

    private static Location square() {
        return new Location("village-square", "Village Square", "A muddy square.");
    }

    @Test
    void newLocationIsUnvisitedWithNoExitsOrItems() {
        Location square = square();

        assertEquals("village-square", square.getId());
        assertEquals("Village Square", square.getName());
        assertEquals("A muddy square.", square.getDescription());
        assertFalse(square.isVisited());
        assertTrue(square.getExits().isEmpty());
        assertTrue(square.getItems().isEmpty());
    }

    @Test
    void markVisitedIsRemembered() {
        Location square = square();
        square.markVisited();
        assertTrue(square.isVisited());
    }

    @Test
    void exitsAreFoundByDirectionAndByDestinationNameIgnoringCase() {
        Location square = square();
        Location tavern = new Location("tavern", "Tavern", "A smoky room.");
        Exit north = new Exit("square-north", Direction.NORTH, tavern);
        square.addExit(north);

        assertSame(north, square.getExit(Direction.NORTH));
        assertNull(square.getExit(Direction.SOUTH));
        assertSame(north, square.findExitTo("tavern"));
        assertSame(north, square.findExitTo(" TAVERN "));
        assertNull(square.findExitTo("Wolf Den"));
        assertNull(square.findExitTo(null));
        assertEquals(List.of(north), square.getExits());
    }

    @Test
    void onlyOneExitPerDirection() {
        Location square = square();
        Location tavern = new Location("tavern", "Tavern", "A smoky room.");
        Location forge = new Location("forge", "Forge", "A warm forge.");
        square.addExit(new Exit("square-north", Direction.NORTH, tavern));

        assertThrows(IllegalArgumentException.class,
                () -> square.addExit(new Exit("square-north-2", Direction.NORTH, forge)));
        assertEquals(1, square.getExits().size());
    }

    @Test
    void itemsCanBeAddedFoundAndRemovedIgnoringCase() {
        Location square = square();
        KeyItem key = new KeyItem("iron key", 0.2);
        square.addItem(key);

        assertSame(key, square.findItem("Iron Key"));
        assertSame(key, square.removeItem("IRON KEY"));
        assertNull(square.findItem("iron key"));
        assertNull(square.removeItem("iron key"));
        assertNull(square.findItem(null));
    }

    @Test
    void exitsAndItemsCannotBeModifiedFromOutside() {
        Location square = square();
        assertThrows(UnsupportedOperationException.class, () -> square.getItems().add(new KeyItem("key", 0.1)));
        assertThrows(UnsupportedOperationException.class,
                () -> square.getExits().add(new Exit("x", Direction.EAST, square())));
    }

    @Test
    void describeShowsNameDescriptionItemsAndExits() {
        Location square = square();
        Location shed = new Location("tool-shed", "Tool Shed", "A cramped shed.");
        square.addExit(new Exit("square-east", Direction.EAST, shed, "shed key"));
        square.addExit(new Exit("square-south", Direction.SOUTH, new Location("forest-edge", "Forest Edge", "Pines.")));
        square.addItem(new Consumable("bread", 0.3, 2));

        assertEquals("Village Square\nA muddy square.\nItems: bread (0.3 kg)\n"
                + "Exits: east (Tool Shed, locked), south (Forest Edge)", square.describe());
        assertEquals("Tool Shed\nA cramped shed.\nItems: none\nExits: none", shed.describe());
    }

    @Test
    void blankOrNullFieldsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Location(" ", "Square", "Muddy."));
        assertThrows(IllegalArgumentException.class, () -> new Location("square", "", "Muddy."));
        assertThrows(NullPointerException.class, () -> new Location("square", "Square", null));
        assertThrows(NullPointerException.class, () -> square().addExit(null));
        assertThrows(NullPointerException.class, () -> square().addItem(null));
    }
}
