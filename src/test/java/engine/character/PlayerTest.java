package engine.character;

import engine.items.Consumable;
import engine.items.Inventory;
import engine.items.Item;
import engine.items.KeyItem;
import engine.items.Weapon;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerTest {

    private static Player newPlayer(Stats stats, List<Item> kit) {
        return new Player("Ari", CharacterClass.WARRIOR, stats, 20, kit);
    }

    @Test
    void validStatsAreAccepted() {
        Stats stats = new Stats(4, 3, 3);

        Player player = newPlayer(stats, List.of());

        assertSame(stats, player.getStats());
        assertEquals(20, player.hp);
        assertEquals(20, player.maxHp);
        assertEquals("Ari", player.name);
        assertTrue(player.isAlive());
    }

    @ParameterizedTest(name = "{0}/{1}/{2} is rejected")
    @CsvSource({
            "3, 3, 3",
            "4, 4, 3",
            "0, 5, 5",
            "9, 1, 0"
    })
    void invalidStatsAreRejected(int str, int agi, int intel) {
        Stats stats = new Stats(str, agi, intel);
        assertThrows(IllegalArgumentException.class, () -> newPlayer(stats, List.of()));
    }

    @Test
    void nullArgumentsAreRejected() {
        Stats stats = new Stats(4, 3, 3);
        assertThrows(NullPointerException.class, () -> newPlayer(null, List.of()));
        assertThrows(NullPointerException.class, () -> newPlayer(stats, null));
        assertThrows(NullPointerException.class,
                () -> new Player("Ari", null, stats, 20, List.of()));
        assertThrows(NullPointerException.class,
                () -> new Player(null, CharacterClass.MAGE, stats, 20, List.of()));
    }

    @Test
    void blankNameAndNonPositiveHpAreRejected() {
        Stats stats = new Stats(4, 3, 3);
        assertThrows(IllegalArgumentException.class,
                () -> new Player(" ", CharacterClass.ROGUE, stats, 20, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new Player("Ari", CharacterClass.ROGUE, stats, 0, List.of()));
    }

    @Test
    void startingKitIsPutIntoTheInventory() {
        Weapon sword = new Weapon("sword", 3.0, 6);
        KeyItem key = new KeyItem("iron key", 0.2);

        Player player = newPlayer(new Stats(4, 3, 3), List.of(sword, key));

        Inventory inventory = player.getInventory();
        assertSame(sword, inventory.find("sword"));
        assertSame(key, inventory.find("iron key"));
        assertEquals(3.2, inventory.totalWeight(), 1e-9);
    }

    @Test
    void startingKitOverTheWeightLimitIsRejected() {
        List<Item> kit = List.of(new Weapon("greatsword", 6.0, 9), new Weapon("warhammer", 4.5, 8));
        assertThrows(IllegalArgumentException.class, () -> newPlayer(new Stats(4, 3, 3), kit));
    }

    @Test
    void healRestoresHpButNeverAboveTheMaximum() {
        Player player = newPlayer(new Stats(4, 3, 3), List.of());
        player.takeDamage(12);
        assertEquals(8, player.hp);

        player.heal(5);
        assertEquals(13, player.hp);

        player.heal(100);
        assertEquals(20, player.hp);
    }

    @Test
    void takeDamageNeverGoesBelowZeroAndKillsAtZero() {
        Player player = newPlayer(new Stats(4, 3, 3), List.of());
        player.takeDamage(19);
        assertTrue(player.isAlive());

        player.takeDamage(50);
        assertEquals(0, player.hp);
        assertFalse(player.isAlive());
    }

    @Test
    void negativeHealOrDamageIsRejected() {
        Player player = newPlayer(new Stats(4, 3, 3), List.of());
        assertThrows(IllegalArgumentException.class, () -> player.heal(-1));
        assertThrows(IllegalArgumentException.class, () -> player.takeDamage(-1));
    }

    @Test
    void newPlayerHasNoEquippedWeaponAndNoBackstory() {
        Player player = newPlayer(new Stats(4, 3, 3), List.of(new Weapon("sword", 3.0, 6)));
        assertNull(player.getEquippedWeapon());
        assertNull(player.getBackstory());
    }

    @Test
    void equipSetsTheEquippedWeapon() {
        Player player = newPlayer(new Stats(4, 3, 3), List.of());
        Weapon dagger = new Weapon("dagger", 1.0, 4);
        Weapon sword = new Weapon("sword", 3.0, 6);

        player.equip(dagger);
        assertSame(dagger, player.getEquippedWeapon());

        player.equip(sword);
        assertSame(sword, player.getEquippedWeapon());
    }

    @Test
    void equipRejectsNullAndKeepsTheCurrentWeapon() {
        Player player = newPlayer(new Stats(4, 3, 3), List.of());
        Weapon dagger = new Weapon("dagger", 1.0, 4);
        player.equip(dagger);

        assertThrows(NullPointerException.class, () -> player.equip(null));
        assertSame(dagger, player.getEquippedWeapon());
    }

    @Test
    void setBackstoryStoresTheText() {
        Player player = newPlayer(new Stats(4, 3, 3), List.of());
        String text = "Ari grew up at the village forge.";

        player.setBackstory(text);
        assertEquals(text, player.getBackstory());

        player.setBackstory("A regenerated backstory.");
        assertEquals("A regenerated backstory.", player.getBackstory());
    }

    @Test
    void consumablesKeepTheirHealAmount() {
        Consumable potion = new Consumable("healing potion", 0.5, 10);
        Player player = newPlayer(new Stats(4, 3, 3), List.of(potion));
        assertNotNull(player.getInventory().find("Healing Potion"));
        assertEquals(10, potion.getHealAmount());
    }
}
