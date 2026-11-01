package engine.commands;

import engine.character.CharacterClass;
import engine.character.CharacterFactory;
import engine.character.Player;
import engine.character.Stats;
import engine.core.CombatMode;
import engine.events.EventType;
import engine.events.GameEvent;
import engine.items.Container;
import engine.items.Inventory;
import engine.items.Item;
import engine.items.Weapon;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EquipCommandTest {

    @Test
    void typeIsEquip() {
        assertEquals(ActionType.EQUIP, new EquipCommand("sword").getType());
    }

    @ParameterizedTest
    @EnumSource(CharacterClass.class)
    void theStartingWeaponIsNotEquipped(CharacterClass cls) {
        Player player = CharacterFactory.forClass(cls).createCharacter("Ari", new Stats(4, 3, 3));

        assertNull(player.getEquippedWeapon());
        assertEquals(Player.UNARMED_DAMAGE, player.damage());
    }

    @Test
    void equippingTheSwordRaisesTheDamageAndKeepsItInTheInventory() {
        CommandTestGame game = new CommandTestGame(CharacterClass.WARRIOR, "village-square");
        Inventory inventory = game.ari.getInventory();
        Item sword = inventory.find("sword");
        double weight = inventory.totalWeight();

        ActionResult result = game.run(new EquipCommand("Sword"));

        assertTrue(result.isSuccess());
        assertEquals("You equip the sword (damage 6).", result.getMessage());
        assertSame(sword, game.ari.getEquippedWeapon());
        assertEquals(6, game.ari.damage());
        assertSame(sword, inventory.find("sword"), "an equipped weapon is still carried");
        assertEquals(weight, inventory.totalWeight(), 1e-9);
        assertEquals(2, game.state.getTurn());
        GameEvent event = game.published.get(0);
        assertEquals(EventType.ITEM, event.getType());
        assertEquals("equipped the sword", event.getDescription());
        assertEquals("sword", event.getSubjectId());
        assertNull(event.getVerb());
        assertEquals(game.published, result.getEvents());
    }

    @Test
    void equippingAnotherWeaponSwapsAndTheOldOneStaysInTheInventory() {
        CommandTestGame game = new CommandTestGame(CharacterClass.WARRIOR, "village-square");
        Inventory inventory = game.ari.getInventory();
        Weapon axe = new Weapon("hand axe", 2.0, 5);
        inventory.add(axe);
        game.run(new EquipCommand("sword"));
        double weight = inventory.totalWeight();

        ActionResult result = game.run(new EquipCommand("hand axe"));

        assertEquals("You equip the hand axe (damage 5) and put away the sword.", result.getMessage());
        assertSame(axe, game.ari.getEquippedWeapon());
        assertEquals(5, game.ari.damage());
        assertInstanceOf(Weapon.class, inventory.find("sword"));
        assertEquals(weight, inventory.totalWeight(), 1e-9);
    }

    @Test
    void aPackedWeaponIsTakenOutOfItsContainerFirst() {
        CommandTestGame game = new CommandTestGame(CharacterClass.ROGUE, "village-square");
        Inventory inventory = game.ari.getInventory();
        Item dagger = inventory.remove("dagger");
        Container pouch = (Container) inventory.find("pouch");
        pouch.add(dagger);
        double weight = inventory.totalWeight();

        ActionResult result = game.run(new EquipCommand("dagger"));

        assertEquals("You take the dagger out of the pouch.\nYou equip the dagger (damage 4).", result.getMessage());
        assertSame(dagger, game.ari.getEquippedWeapon());
        assertTrue(inventory.getItems().contains(dagger));
        assertFalse(pouch.contains(dagger));
        assertEquals(weight, inventory.totalWeight(), 1e-9);
    }

    @Test
    void theEquippedWeaponDealsItsDamageInTheNextAttack() {
        CommandTestGame game = new CommandTestGame(CharacterClass.WARRIOR, "wolf-den");
        game.run(new EquipCommand("sword"));

        ActionResult attack = game.run(new AttackCommand("wolf"), 10, 1);   // 10 + 4 = 14 >= 11 hit; the wolf misses

        assertTrue(attack.getMessage().startsWith(
                "Ari attacks wolf: rolls 10 + 4 = 14 against defense 11. Hit for 6 damage (wolf: 2/8 HP)."),
                attack.getMessage());
    }

    @Test
    void inCombatTheEnemyTakesItsTurnAfterwards() {
        CommandTestGame game = new CommandTestGame(CharacterClass.WARRIOR, "wolf-den");
        game.fight("wolf");

        ActionResult result = game.run(new EquipCommand("sword"), 15);   // 15 + 3 = 18 >= 13: the wolf hits

        assertTrue(result.isSuccess());
        assertEquals("You equip the sword (damage 6).\n"
                + "wolf attacks Ari: rolls 15 + 3 = 18 against defense 13. Hit for 3 damage (Ari: 27/30 HP).",
                result.getMessage());
        assertEquals(6, game.ari.damage());
        assertEquals(27, game.ari.getHp());
        assertInstanceOf(CombatMode.class, game.state.getMode());
        assertEquals(List.of(EventType.ITEM, EventType.COMBAT), game.publishedTypes());
    }

    @Test
    void aWeaponThePlayerDoesNotCarryIsRejected() {
        CommandTestGame game = new CommandTestGame(CharacterClass.WARRIOR, "village-square");
        game.assertRejectedWithoutChange(new EquipCommand("warhammer"), "You don't carry the warhammer.");
    }

    @Test
    void aWeaponLyingHereMustBeTakenFirst() {
        CommandTestGame game = new CommandTestGame(CharacterClass.WARRIOR, "forge");
        game.assertRejectedWithoutChange(new EquipCommand("warhammer"),
                "You don't carry the warhammer. It is here: take it first.");
    }

    @Test
    void onlyAWeaponCanBeEquipped() {
        CommandTestGame game = new CommandTestGame(CharacterClass.WARRIOR, "village-square");
        game.assertRejectedWithoutChange(new EquipCommand("healing potion"), "You can't equip the healing potion.");
    }

    @Test
    void theEquippedWeaponCannotBeEquippedAgain() {
        CommandTestGame game = new CommandTestGame(CharacterClass.WARRIOR, "village-square");
        game.run(new EquipCommand("sword"));

        game.assertRejectedWithoutChange(new EquipCommand("sword"), "You already wield the sword.");
    }

    @Test
    void aRejectedEquipInCombatGivesTheEnemyNoTurn() {
        CommandTestGame game = new CommandTestGame(CharacterClass.WARRIOR, "wolf-den");
        game.fight("wolf");

        game.assertRejectedWithoutChange(new EquipCommand("healing potion"), "You can't equip the healing potion.");
    }
}
