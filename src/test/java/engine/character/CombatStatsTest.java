package engine.character;

import engine.items.Weapon;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatStatsTest {

    @Test
    void playerAttackBonusIsStrengthAndDefenseIsTenPlusAgility() {
        Player player = new Player("Ari", CharacterClass.ROGUE, new Stats(2, 6, 2), 24, List.of());

        assertEquals(2, player.attackBonus());
        assertEquals(Player.BASE_DEFENSE + 6, player.defense());
        assertEquals(16, player.defense());
    }

    @Test
    void playerDamageIsTheEquippedWeaponsOrUnarmed() {
        Player player = new Player("Ari", CharacterClass.WARRIOR, new Stats(4, 3, 3), 30, List.of());
        assertEquals(Player.UNARMED_DAMAGE, player.damage());
        assertEquals(2, player.damage());

        player.equip(new Weapon("sword", 3.0, 6));

        assertEquals(6, player.damage());
    }

    @Test
    void enemyUsesItsAttackForBonusAndDamageAndItsRatingForDefense() {
        Enemy wolf = new Enemy("wolf", 8, 3, 11);

        assertEquals(3, wolf.attackBonus());
        assertEquals(3, wolf.damage());
        assertEquals(11, wolf.defense());
        assertEquals("wolf", wolf.getName());
        assertEquals(8, wolf.getHp());
        assertEquals(8, wolf.getMaxHp());
        assertTrue(wolf.isAlive());
    }

    @Test
    void invalidEnemiesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Enemy(" ", 8, 3, 11));
        assertThrows(IllegalArgumentException.class, () -> new Enemy("wolf", 0, 3, 11));
        assertThrows(IllegalArgumentException.class, () -> new Enemy("wolf", 8, -1, 11));
        assertThrows(IllegalArgumentException.class, () -> new Enemy("wolf", 8, 3, -1));
        assertThrows(NullPointerException.class, () -> new Enemy(null, 8, 3, 11));
    }
}
