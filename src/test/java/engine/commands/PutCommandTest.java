package engine.commands;

import engine.character.CharacterClass;
import engine.events.EventType;
import engine.events.GameEvent;
import engine.items.Container;
import engine.items.Inventory;
import engine.items.Item;
import engine.items.KeyItem;
import engine.quests.Quest;
import engine.quests.QuestStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rogue starts with a dagger (1 kg) and a pouch (0.2 kg, capacity 4) holding a healing potion (0.5 kg). */
class PutCommandTest {

    private static List<String> names(Container container) {
        return container.getContents().stream().map(Item::getName).toList();
    }

    @Test
    void typeIsPut() {
        assertEquals(ActionType.PUT, new PutCommand("dagger", "pouch").getType());
    }

    @Test
    void aCarriedItemGoesIntoACarriedContainerAndTheWeightStaysTheSame() {
        CommandTestGame game = new CommandTestGame(CharacterClass.ROGUE, "village-square");
        Inventory inventory = game.ari.getInventory();
        Container pouch = (Container) inventory.find("pouch");

        ActionResult result = game.run(new PutCommand("Dagger", "Pouch"));

        assertTrue(result.isSuccess());
        assertEquals("You put the dagger in the pouch.", result.getMessage());
        assertEquals(List.of("healing potion", "dagger"), names(pouch));
        assertEquals(List.of(pouch), inventory.getItems());
        assertEquals(1.7, inventory.totalWeight(), 1e-9);
        GameEvent put = game.published.get(0);
        assertEquals(EventType.ITEM, put.getType());
        assertEquals("put the dagger in the pouch", put.getDescription());
        assertEquals("dagger", put.getSubjectId());
        assertNull(put.getVerb());
    }

    @Test
    void itemsInANestedContainerCountTowardsTheTotalWeight() {
        CommandTestGame game = new CommandTestGame(CharacterClass.ROGUE, "tool-shed");
        Inventory inventory = game.ari.getInventory();
        game.run(new TakeCommand("backpack"));   // 1 kg, so 2.7 kg in all

        assertTrue(game.run(new PutCommand("pouch", "backpack")).isSuccess());
        assertTrue(game.run(new PutCommand("dagger", "pouch")).isSuccess());   // into the pouch inside the backpack

        Container backpack = (Container) inventory.find("backpack");
        assertEquals(List.of(backpack), inventory.getItems());
        assertEquals(2.7, backpack.getWeight(), 1e-9);
        assertEquals(2.7, inventory.totalWeight(), 1e-9);
        assertFalse(inventory.canCarry(new KeyItem("anvil", 7.4)), "10.1 kg would be over the limit");
        assertTrue(inventory.canCarry(new KeyItem("anvil", 7.3)));
    }

    @Test
    void packingTheEquippedWeaponUnequipsIt() {
        CommandTestGame game = new CommandTestGame(CharacterClass.ROGUE, "village-square");
        game.run(new EquipCommand("dagger"));

        ActionResult result = game.run(new PutCommand("dagger", "pouch"));

        assertEquals("You put the dagger in the pouch. You no longer wield it.", result.getMessage());
        assertNull(game.ari.getEquippedWeapon());
        assertEquals(2, game.ari.damage());
    }

    @Test
    void aPutNeverCountsForAFetchObjective() {
        CommandTestGame game = new CommandTestGame(CharacterClass.ROGUE, "village-square");
        Quest quest = game.fetchQuest("dagger");

        game.run(new PutCommand("dagger", "pouch"));

        assertEquals(QuestStatus.ACTIVE, quest.getStatus());
        assertEquals(0, quest.getObjectives().get(0).getProgress());
    }

    @Test
    void theItemAndTheContainerMustBeCarried() {
        CommandTestGame game = new CommandTestGame(CharacterClass.ROGUE, "tool-shed");
        game.assertRejectedWithoutChange(new PutCommand("hand axe", "pouch"),
                "You don't carry the hand axe. It is here: take it first.");
        game.assertRejectedWithoutChange(new PutCommand("dagger", "backpack"),
                "You don't carry the backpack. It is here: take it first.");
        game.assertRejectedWithoutChange(new PutCommand("dagger", "sack"), "You don't carry the sack.");
    }

    @Test
    void theTargetMustBeAContainer() {
        CommandTestGame game = new CommandTestGame(CharacterClass.ROGUE, "village-square");
        game.assertRejectedWithoutChange(new PutCommand("healing potion", "dagger"), "The dagger is not a container.");
    }

    @Test
    void aContainerCannotGoIntoItselfOrIntoAContainerItHolds() {
        CommandTestGame game = new CommandTestGame(CharacterClass.ROGUE, "tool-shed");
        game.assertRejectedWithoutChange(new PutCommand("pouch", "pouch"), "You can't put the pouch into itself.");
        game.run(new TakeCommand("backpack"));
        game.run(new PutCommand("pouch", "backpack"));

        game.assertRejectedWithoutChange(new PutCommand("backpack", "pouch"),
                "You can't put the backpack into the pouch: the pouch is inside it.");
    }

    @Test
    void anItemAlreadyInTheContainerIsRejected() {
        CommandTestGame game = new CommandTestGame(CharacterClass.ROGUE, "village-square");
        game.assertRejectedWithoutChange(new PutCommand("healing potion", "pouch"),
                "The healing potion is already in the pouch.");
    }

    @Test
    void aFullContainerIsRejected() {
        CommandTestGame game = new CommandTestGame(CharacterClass.ROGUE, "village-square");
        Container pouch = (Container) game.ari.getInventory().find("pouch");
        pouch.add(new KeyItem("brass key", 0.1));
        pouch.add(new KeyItem("copper key", 0.1));
        pouch.add(new KeyItem("silver key", 0.1));

        game.assertRejectedWithoutChange(new PutCommand("dagger", "pouch"), "The pouch is full (4 items).");
    }

    @Test
    void puttingIsNotPossibleInCombat() {
        CommandTestGame game = new CommandTestGame(CharacterClass.ROGUE, "wolf-den");
        game.fight("wolf");
        game.assertRejectedWithoutChange(new PutCommand("dagger", "pouch"),
                "You can't pack things away during the fight with the wolf. You can attack, flee, use or equip an item, look around or check your inventory.");
    }
}
