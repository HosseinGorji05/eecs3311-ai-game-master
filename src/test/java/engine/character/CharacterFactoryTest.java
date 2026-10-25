package engine.character;

import engine.items.Inventory;
import engine.items.Item;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CharacterFactoryTest {

    private static final Stats VALID_STATS = new Stats(4, 3, 3);

    @Test
    void forClassReturnsTheConcreteFactoryForEachClass() {
        assertInstanceOf(WarriorFactory.class, CharacterFactory.forClass(CharacterClass.WARRIOR));
        assertInstanceOf(RogueFactory.class, CharacterFactory.forClass(CharacterClass.ROGUE));
        assertInstanceOf(MageFactory.class, CharacterFactory.forClass(CharacterClass.MAGE));
    }

    @Test
    void forClassRejectsNull() {
        assertThrows(NullPointerException.class, () -> CharacterFactory.forClass(null));
    }

    @ParameterizedTest
    @EnumSource(CharacterClass.class)
    void everyClassPresetHasPositiveHpAndAStartingKitWithinTheWeightLimit(CharacterClass cls) {
        CharacterFactory factory = CharacterFactory.forClass(cls);

        assertTrue(factory.baseHp() > 0);
        List<Item> kit = factory.createStartingKit();
        assertFalse(kit.isEmpty());
        double kitWeight = kit.stream().mapToDouble(Item::getWeight).sum();
        assertTrue(kitWeight <= Inventory.MAX_CARRY_WEIGHT, "kit weighs " + kitWeight + " kg");
    }

    @ParameterizedTest
    @EnumSource(CharacterClass.class)
    void everyClassBuildsAValidPlayerAtFullBaseHpCarryingItsKit(CharacterClass cls) {
        CharacterFactory factory = CharacterFactory.forClass(cls);
        List<Item> kit = factory.createStartingKit();

        Player player = factory.createCharacter("Ari", VALID_STATS);

        assertNotNull(player);
        assertTrue(player.getStats().isValid());
        assertEquals(factory.baseHp(), player.hp);
        assertEquals(factory.baseHp(), player.maxHp);
        assertTrue(player.isAlive());
        double kitWeight = kit.stream().mapToDouble(Item::getWeight).sum();
        assertEquals(kitWeight, player.getInventory().totalWeight(), 1e-9);
        for (Item item : kit) {
            assertNotNull(player.getInventory().find(item.getName()), "missing " + item.getName());
        }
    }

    @Test
    void eachCallCreatesAFreshStartingKit() {
        CharacterFactory factory = CharacterFactory.forClass(CharacterClass.WARRIOR);
        assertNotSame(factory.createStartingKit().get(0), factory.createStartingKit().get(0));
    }

    @Test
    void classesDifferInHp() {
        int warrior = CharacterFactory.forClass(CharacterClass.WARRIOR).baseHp();
        int rogue = CharacterFactory.forClass(CharacterClass.ROGUE).baseHp();
        int mage = CharacterFactory.forClass(CharacterClass.MAGE).baseHp();
        assertTrue(warrior > rogue && rogue > mage);
    }

    @ParameterizedTest
    @EnumSource(CharacterClass.class)
    void createCharacterRejectsInvalidStats(CharacterClass cls) {
        CharacterFactory factory = CharacterFactory.forClass(cls);
        assertThrows(IllegalArgumentException.class, () -> factory.createCharacter("Ari", new Stats(3, 3, 3)));
        assertThrows(IllegalArgumentException.class, () -> factory.createCharacter("Ari", new Stats(9, 1, 0)));
    }

    @Test
    void createCharacterRejectsABlankName() {
        CharacterFactory factory = CharacterFactory.forClass(CharacterClass.MAGE);
        assertThrows(IllegalArgumentException.class, () -> factory.createCharacter("   ", VALID_STATS));
    }
}
