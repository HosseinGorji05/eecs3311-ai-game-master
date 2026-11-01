package engine.commands;

import engine.character.CharacterClass;
import engine.events.EventType;
import engine.events.GameEvent;
import engine.items.Consumable;
import engine.items.Container;
import engine.items.Inventory;
import engine.items.Item;
import engine.quests.Quest;
import engine.quests.QuestStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DropCommandTest {

    @Test
    void typeIsDrop() {
        assertEquals(ActionType.DROP, new DropCommand("bread").getType());
    }

    @Test
    void aDroppedItemLeavesTheInventoryAndLiesInTheLocation() {
        CommandTestGame game = new CommandTestGame(CharacterClass.WARRIOR, "village-square");
        Inventory inventory = game.ari.getInventory();
        Item potion = inventory.find("healing potion");

        ActionResult result = game.run(new DropCommand("Healing Potion"));

        assertTrue(result.isSuccess());
        assertEquals("You drop the healing potion.", result.getMessage());
        assertNull(inventory.find("healing potion"));
        assertSame(potion, game.state.currentLocation().findItem("healing potion"));
        assertEquals(3.0, inventory.totalWeight(), 1e-9);
        GameEvent dropped = game.published.get(0);
        assertEquals(EventType.ITEM, dropped.getType());
        assertEquals("dropped the healing potion in Village Square", dropped.getDescription());
        assertEquals("healing potion", dropped.getSubjectId());
        assertNull(dropped.getVerb());
    }

    @Test
    void droppingTheEquippedWeaponUnequipsIt() {
        CommandTestGame game = new CommandTestGame(CharacterClass.WARRIOR, "village-square");
        game.run(new EquipCommand("sword"));

        ActionResult result = game.run(new DropCommand("sword"));

        assertEquals("You drop the sword. You no longer wield it.", result.getMessage());
        assertNull(game.ari.getEquippedWeapon());
        assertEquals(2, game.ari.damage());
    }

    @Test
    void aDroppedContainerKeepsItsContents() {
        CommandTestGame game = new CommandTestGame(CharacterClass.ROGUE, "village-square");

        game.run(new DropCommand("pouch"));

        Container pouch = (Container) game.state.currentLocation().findItem("pouch");
        assertEquals(List.of("healing potion"), pouch.getContents().stream().map(Item::getName).toList());
        assertNull(game.ari.getInventory().find("healing potion"));
        assertEquals(1.0, game.ari.getInventory().totalWeight(), 1e-9);
    }

    @Test
    void anItemInsideAContainerCanBeDropped() {
        CommandTestGame game = new CommandTestGame(CharacterClass.ROGUE, "village-square");
        Container pouch = (Container) game.ari.getInventory().find("pouch");

        game.run(new DropCommand("healing potion"));

        assertTrue(pouch.getContents().isEmpty());
        assertTrue(game.ari.getInventory().getItems().contains(pouch));
        assertInstanceOf(Consumable.class, game.state.currentLocation().findItem("healing potion"));
    }

    @Test
    void droppingAContainerThatHoldsTheEquippedWeaponUnequipsIt() {
        CommandTestGame game = new CommandTestGame(CharacterClass.ROGUE, "village-square");
        Inventory inventory = game.ari.getInventory();
        game.run(new EquipCommand("dagger"));
        Item dagger = inventory.remove("dagger");   // packed without PutCommand, which would unequip it
        ((Container) inventory.find("pouch")).add(dagger);

        ActionResult result = game.run(new DropCommand("pouch"));

        assertEquals("You drop the pouch. You no longer wield the dagger.", result.getMessage());
        assertNull(game.ari.getEquippedWeapon());
    }

    @Test
    void droppingThenTakingRespectsTheWeightLimit() {
        CommandTestGame game = new CommandTestGame(CharacterClass.WARRIOR, "forge");   // sword 3 + potion 0.5
        game.assertRejectedWithoutChange(new TakeCommand("warhammer"),
                "The warhammer is too heavy (8 kg). You carry 3.5 of 10 kg.");

        assertTrue(game.run(new DropCommand("sword")).isSuccess());
        assertTrue(game.run(new TakeCommand("warhammer")).isSuccess());

        assertEquals(8.5, game.ari.getInventory().totalWeight(), 1e-9);
        game.assertRejectedWithoutChange(new TakeCommand("sword"), "The sword is too heavy (3 kg). You carry 8.5 of 10 kg.");
        assertNotNull(game.state.currentLocation().findItem("sword"), "the sword stays where it is");
    }

    @Test
    void aDropNeverCountsForAFetchObjectiveButTakingItBackDoes() {
        CommandTestGame game = new CommandTestGame(CharacterClass.WARRIOR, "village-square");
        Quest quest = game.fetchQuest("healing potion");

        game.run(new DropCommand("healing potion"));

        assertEquals(QuestStatus.ACTIVE, quest.getStatus());
        assertEquals(0, quest.getObjectives().get(0).getProgress());

        game.run(new TakeCommand("healing potion"));

        assertEquals(QuestStatus.COMPLETED, quest.getStatus());
    }

    @Test
    void anItemThePlayerDoesNotCarryIsRejected() {
        CommandTestGame game = new CommandTestGame(CharacterClass.WARRIOR, "tavern");
        game.assertRejectedWithoutChange(new DropCommand("bread"), "You don't carry the bread. It is here: take it first.");
        game.assertRejectedWithoutChange(new DropCommand("dragon"), "You don't carry the dragon.");
    }

    @Test
    void droppingIsNotPossibleInCombat() {
        CommandTestGame game = new CommandTestGame(CharacterClass.WARRIOR, "wolf-den");
        game.fight("wolf");
        game.assertRejectedWithoutChange(new DropCommand("sword"), "That is not possible right now.");
    }
}
