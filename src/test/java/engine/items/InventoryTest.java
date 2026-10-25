package engine.items;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryTest {

    @Test
    void defaultLimitIsTenKilograms() {
        assertEquals(10.0, Inventory.MAX_CARRY_WEIGHT);
        assertTrue(new Inventory().canCarry(new KeyItem("anvil", 10.0)));
        assertFalse(new Inventory().canCarry(new KeyItem("anvil", 10.1)));
    }

    @Test
    void newInventoryIsEmpty() {
        Inventory inventory = new Inventory();
        assertEquals(0.0, inventory.totalWeight(), 1e-9);
        assertNull(inventory.find("sword"));
    }

    @Test
    void addedItemCanBeFoundIgnoringCase() {
        Inventory inventory = new Inventory();
        Weapon sword = new Weapon("sword", 3.0, 6);

        assertTrue(inventory.add(sword));

        assertSame(sword, inventory.find("sword"));
        assertSame(sword, inventory.find("SWORD"));
        assertEquals(3.0, inventory.totalWeight(), 1e-9);
    }

    @Test
    void removeReturnsTheItemAndLowersTheWeight() {
        Inventory inventory = new Inventory();
        Weapon sword = new Weapon("sword", 3.0, 6);
        KeyItem key = new KeyItem("iron key", 0.2);
        inventory.add(sword);
        inventory.add(key);

        assertSame(sword, inventory.remove("sword"));

        assertNull(inventory.find("sword"));
        assertSame(key, inventory.find("iron key"));
        assertEquals(0.2, inventory.totalWeight(), 1e-9);
    }

    @Test
    void removingOrFindingAMissingItemReturnsNull() {
        Inventory inventory = new Inventory();
        inventory.add(new KeyItem("iron key", 0.2));

        assertNull(inventory.remove("dragon"));
        assertNull(inventory.find("dragon"));
        assertNull(inventory.find(null));
        assertNull(inventory.remove(null));
        assertEquals(0.2, inventory.totalWeight(), 1e-9);
    }

    @Test
    void totalWeightCountsItemsInsideNestedContainers() {
        Inventory inventory = new Inventory();
        Container backpack = new Container("backpack", 1.0, 10);
        Container pouch = new Container("pouch", 0.2, 4);
        pouch.add(new Consumable("healing potion", 0.5, 10));
        pouch.add(new KeyItem("iron key", 0.2));
        backpack.add(pouch);
        backpack.add(new Weapon("sword", 3.0, 6));

        assertTrue(inventory.add(backpack));
        assertTrue(inventory.add(new Consumable("bread", 0.3, 2)));

        // backpack 1.0 + pouch 0.2 + potion 0.5 + key 0.2 + sword 3.0 + bread 0.3
        assertEquals(5.2, inventory.totalWeight(), 1e-9);
    }

    @Test
    void findAndRemoveReachItemsInsideNestedContainers() {
        Inventory inventory = new Inventory();
        Container backpack = new Container("backpack", 1.0, 10);
        Container pouch = new Container("pouch", 0.2, 4);
        KeyItem key = new KeyItem("iron key", 0.2);
        pouch.add(key);
        backpack.add(pouch);
        inventory.add(backpack);

        assertSame(pouch, inventory.find("pouch"));
        assertSame(key, inventory.find("iron key"));

        assertSame(key, inventory.remove("iron key"));
        assertTrue(pouch.getContents().isEmpty());
        assertNull(inventory.find("iron key"));
        assertEquals(1.2, inventory.totalWeight(), 1e-9);
    }

    @Test
    void removingAContainerRemovesEverythingInsideIt() {
        Inventory inventory = new Inventory();
        Container pouch = new Container("pouch", 0.2, 4);
        pouch.add(new KeyItem("iron key", 0.2));
        inventory.add(pouch);
        inventory.add(new Weapon("sword", 3.0, 6));

        assertSame(pouch, inventory.remove("pouch"));

        assertNull(inventory.find("iron key"));
        assertEquals(3.0, inventory.totalWeight(), 1e-9);
    }

    @Test
    void itemOverTheLimitIsRejectedAndNothingChanges() {
        Inventory inventory = new Inventory();
        inventory.add(new Weapon("sword", 3.0, 6));
        Weapon greatsword = new Weapon("greatsword", 7.5, 9);

        assertFalse(inventory.canCarry(greatsword));
        assertFalse(inventory.add(greatsword));

        assertNull(inventory.find("greatsword"));
        assertEquals(3.0, inventory.totalWeight(), 1e-9);
    }

    @Test
    void containerWhoseContentsPushItOverTheLimitIsRejected() {
        Inventory inventory = new Inventory();
        inventory.add(new Weapon("sword", 3.0, 6));
        Container sack = new Container("sack", 0.5, 10);
        sack.add(new KeyItem("anvil", 6.6));

        assertFalse(inventory.add(sack));
        assertEquals(3.0, inventory.totalWeight(), 1e-9);
    }

    @Test
    void exactlyTenKilogramsIsAllowed() {
        Inventory inventory = new Inventory();
        assertTrue(inventory.add(new KeyItem("anvil", 10.0)));
        assertEquals(10.0, inventory.totalWeight(), 1e-9);
    }

    @Test
    void tenPointOneKilogramsIsRejected() {
        Inventory inventory = new Inventory();
        assertFalse(inventory.add(new KeyItem("anvil", 10.1)));
        assertEquals(0.0, inventory.totalWeight(), 1e-9);
    }

    @Test
    void reachingTheLimitStepByStepIsAllowedButOneMoreGramIsNot() {
        Inventory inventory = new Inventory();
        assertTrue(inventory.add(new Weapon("sword", 3.0, 6)));
        assertTrue(inventory.add(new Weapon("axe", 4.3, 7)));
        assertTrue(inventory.add(new Consumable("potion", 2.7, 10)));   // 3.0 + 4.3 + 2.7 = 10.0
        assertEquals(10.0, inventory.totalWeight(), 1e-9);

        assertFalse(inventory.add(new KeyItem("iron key", 0.001)));
        assertTrue(inventory.add(new KeyItem("feather", 0.0)));
    }

    @Test
    void ninePointFiveKilogramsLeavesRoomForHalfAKilogramOnly() {
        Inventory inventory = new Inventory();
        inventory.add(new Weapon("sword", 9.5, 6));

        assertFalse(inventory.canCarry(new KeyItem("iron key", 0.6)));
        assertTrue(inventory.add(new KeyItem("iron key", 0.5)));
    }

    @Test
    void nullIsNotCarried() {
        Inventory inventory = new Inventory();
        assertFalse(inventory.canCarry(null));
        assertFalse(inventory.add(null));
    }

    @Test
    void sameItemCannotBeAddedTwice() {
        Inventory inventory = new Inventory();
        KeyItem key = new KeyItem("iron key", 0.2);
        assertTrue(inventory.add(key));
        assertFalse(inventory.add(key));
        assertEquals(0.2, inventory.totalWeight(), 1e-9);
    }

    @Test
    void customLimitIsRespected() {
        Inventory inventory = new Inventory(2.0);
        assertTrue(inventory.add(new KeyItem("iron key", 2.0)));
        assertFalse(inventory.add(new KeyItem("brass key", 0.1)));
        assertThrows(IllegalArgumentException.class, () -> new Inventory(-1.0));
    }
}
