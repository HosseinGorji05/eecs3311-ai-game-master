package engine.items;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerTest {

    @Test
    void emptyContainerWeighsItsOwnWeight() {
        Container backpack = new Container("backpack", 1.0, 10);
        assertEquals(1.0, backpack.getWeight(), 1e-9);
        assertTrue(backpack.getContents().isEmpty());
    }

    @Test
    void addAndRemoveChangeContentsAndWeight() {
        Container backpack = new Container("backpack", 1.0, 10);
        Weapon sword = new Weapon("sword", 3.0, 6);
        KeyItem key = new KeyItem("iron key", 0.2);

        assertTrue(backpack.add(sword));
        assertTrue(backpack.add(key));
        assertEquals(List.of(sword, key), backpack.getContents());
        assertEquals(4.2, backpack.getWeight(), 1e-9);

        assertTrue(backpack.remove(sword));
        assertEquals(List.of(key), backpack.getContents());
        assertEquals(1.2, backpack.getWeight(), 1e-9);

        assertFalse(backpack.remove(sword));
    }

    @Test
    void weightIncludesNestedContainersAtAnyDepth() {
        Container backpack = new Container("backpack", 1.0, 10);
        Container pouch = new Container("pouch", 0.2, 4);
        Container tin = new Container("tin", 0.1, 2);
        tin.add(new KeyItem("iron key", 0.2));
        pouch.add(new Consumable("healing potion", 0.5, 10));
        pouch.add(tin);
        backpack.add(pouch);
        backpack.add(new Weapon("sword", 3.0, 6));

        assertEquals(0.3, tin.getWeight(), 1e-9);
        assertEquals(1.0, pouch.getWeight(), 1e-9);
        assertEquals(5.0, backpack.getWeight(), 1e-9);
    }

    @Test
    void containerCannotContainItself() {
        Container backpack = new Container("backpack", 1.0, 10);
        assertFalse(backpack.add(backpack));
        assertTrue(backpack.getContents().isEmpty());
    }

    @Test
    void containerCannotContainItselfThroughANestedContainer() {
        Container backpack = new Container("backpack", 1.0, 10);
        Container pouch = new Container("pouch", 0.2, 4);
        Container tin = new Container("tin", 0.1, 2);
        assertTrue(backpack.add(pouch));
        assertTrue(pouch.add(tin));

        assertFalse(pouch.add(backpack));
        assertFalse(tin.add(backpack));
        assertFalse(tin.add(pouch));
        assertTrue(tin.getContents().isEmpty());
    }

    @Test
    void capacityLimitsTheNumberOfDirectItems() {
        Container pouch = new Container("pouch", 0.2, 2);
        assertTrue(pouch.add(new KeyItem("iron key", 0.2)));
        assertTrue(pouch.add(new KeyItem("brass key", 0.2)));
        assertFalse(pouch.add(new KeyItem("silver key", 0.2)));
        assertEquals(2, pouch.getContents().size());
    }

    @Test
    void sameItemOrNullCannotBeAdded() {
        Container pouch = new Container("pouch", 0.2, 4);
        KeyItem key = new KeyItem("iron key", 0.2);
        assertTrue(pouch.add(key));
        assertFalse(pouch.add(key));
        assertFalse(pouch.add(null));
        assertEquals(1, pouch.getContents().size());
    }

    @Test
    void contentsCannotBeModifiedFromOutside() {
        Container pouch = new Container("pouch", 0.2, 4);
        assertThrows(UnsupportedOperationException.class,
                () -> pouch.getContents().add(new KeyItem("iron key", 0.2)));
    }

    @Test
    void invalidItemsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new KeyItem(" ", 0.2));
        assertThrows(IllegalArgumentException.class, () -> new KeyItem("iron key", -0.1));
        assertThrows(IllegalArgumentException.class, () -> new KeyItem("iron key", Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new Container("pouch", 0.2, -1));
        assertThrows(IllegalArgumentException.class, () -> new Weapon("sword", 3.0, -1));
        assertThrows(IllegalArgumentException.class, () -> new Consumable("potion", 0.5, -1));
    }

    @Test
    void itemsReportNameOwnerAndDescription() {
        KeyItem key = new KeyItem("iron key", 0.2, "npc-blacksmith");
        Container pouch = new Container("pouch", 0.2, 4);
        pouch.add(key);

        assertEquals("iron key", key.getName());
        assertEquals("npc-blacksmith", key.getOwnerId());
        assertEquals(null, pouch.getOwnerId());
        assertEquals("iron key (0.2 kg)", key.describe());
        assertEquals("pouch (0.4 kg)", pouch.describe());
    }
}
