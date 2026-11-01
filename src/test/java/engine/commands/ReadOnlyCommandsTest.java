package engine.commands;

import engine.character.CharacterClass;
import engine.items.Container;
import engine.items.Inventory;
import engine.items.Item;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LookCommand and InventoryCommand: always valid, read-only, without events, in every mode. */
class ReadOnlyCommandsTest {

    /** Runs the command and checks it succeeded without changing anything or publishing an event. */
    private static String runReadOnly(CommandTestGame game, GameCommand command) {
        String before = StateFingerprint.of(game.state);
        int turn = game.state.getTurn();
        game.published.clear();

        ActionResult result = game.run(command);   // no rolls planned: even in combat the enemy does not act

        assertTrue(result.isSuccess());
        assertTrue(result.getEvents().isEmpty());
        assertTrue(game.published.isEmpty());
        assertEquals(before, StateFingerprint.of(game.state));
        assertEquals(turn + 1, game.state.getTurn(), "like every command, it still ends the turn (SD03)");
        return result.getMessage();
    }

    @Test
    void typesAreLookAndInventory() {
        assertEquals(ActionType.LOOK, new LookCommand().getType());
        assertEquals(ActionType.INVENTORY, new InventoryCommand().getType());
    }

    @Test
    void lookDescribesTheLocationItsItemsPeopleAndExits() {
        CommandTestGame game = new CommandTestGame(CharacterClass.WARRIOR, "tavern");

        String message = runReadOnly(game, new LookCommand());

        assertEquals("Tavern\nA smoky room with a low ceiling. A few villagers whisper over their mugs.\n"
                + "Items: shed key (0.1 kg), bread (0.3 kg)\nPeople: Marta (tavern keeper)\nExits: south (Village Square)",
                message);
    }

    @Test
    void lookShowsTheEnemiesAndWorksInCombat() {
        CommandTestGame game = new CommandTestGame(CharacterClass.WARRIOR, "wolf-den");
        game.fight("wolf").takeDamage(2);

        String message = runReadOnly(game, new LookCommand());

        assertTrue(message.contains("\nEnemies: wolf (6/8 HP)\n"), message);
        assertEquals(game.state.currentLocation().describe(), message);
    }

    @Test
    void inventoryListsItemsTheWeaponAndTheWeight() {
        CommandTestGame game = new CommandTestGame(CharacterClass.WARRIOR, "village-square");

        assertEquals("Items: sword (3 kg), healing potion (0.5 kg)\nWeapon: none (unarmed, damage 2)\n"
                + "You carry 3.5 of 10 kg.", runReadOnly(game, new InventoryCommand()));

        game.run(new EquipCommand("sword"));

        assertTrue(runReadOnly(game, new InventoryCommand()).contains("\nWeapon: sword (damage 6)\n"));
    }

    @Test
    void inventoryShowsTheContentsOfNestedContainers() {
        CommandTestGame game = new CommandTestGame(CharacterClass.ROGUE, "tool-shed");
        game.run(new TakeCommand("backpack"));
        game.run(new PutCommand("pouch", "backpack"));

        assertEquals("Items: dagger (1 kg), backpack (1.7 kg: pouch (0.7 kg: healing potion (0.5 kg)))\n"
                + "Weapon: none (unarmed, damage 2)\nYou carry 2.7 of 10 kg.", runReadOnly(game, new InventoryCommand()));
    }

    @Test
    void inventoryShowsEmptyContainersAndAnEmptyInventory() {
        CommandTestGame game = new CommandTestGame(CharacterClass.ROGUE, "village-square");
        Inventory inventory = game.ari.getInventory();
        ((Container) inventory.find("pouch")).remove(inventory.find("healing potion"));

        assertTrue(runReadOnly(game, new InventoryCommand()).startsWith("Items: dagger (1 kg), pouch (0.2 kg: empty)\n"));

        for (Item item : List.copyOf(inventory.getItems())) {
            inventory.remove(item.getName());
        }

        assertEquals("Items: none\nWeapon: none (unarmed, damage 2)\nYou carry 0 of 10 kg.",
                runReadOnly(game, new InventoryCommand()));
    }

    @Test
    void inventoryWorksInCombatWithoutGivingTheEnemyATurn() {
        CommandTestGame game = new CommandTestGame(CharacterClass.MAGE, "wolf-den");
        game.fight("wolf");

        assertTrue(runReadOnly(game, new InventoryCommand()).startsWith(
                "Items: staff (2 kg), healing potion (0.5 kg), healing potion (0.5 kg)\n"));
    }
}
