package presentation;

import engine.character.CharacterClass;
import engine.character.Stats;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The New Game screen's live validation (F01, UC01 3a), without a display. */
class StatAllocationTest {

    @Test
    void theSliderLimitsAreTheStatRulesOfStats() {
        assertTrue(new Stats(StatAllocation.MAX, StatAllocation.MIN, StatAllocation.MIN).isValid());
        assertFalse(new Stats(StatAllocation.MAX + 1, StatAllocation.MIN - 1, StatAllocation.MIN + 1).isValid());
        assertEquals(StatAllocation.TOTAL_POINTS, StatAllocation.MAX + 2 * StatAllocation.MIN);
    }

    @Test
    void thePresetsAreTheClisAndValid() {
        for (CharacterClass cls : CharacterClass.values()) {
            StatAllocation preset = StatAllocation.preset("Ari", cls);
            assertTrue(preset.isValid(), cls.name());
            assertEquals(0, preset.pointsRemaining());
        }
        StatAllocation rogue = StatAllocation.preset("Ari", CharacterClass.ROGUE);
        assertEquals(2, rogue.strength());
        assertEquals(6, rogue.agility());
        assertEquals(2, rogue.intelligence());
    }

    @Test
    void aBlankNameIsReportedFirstWithTheControllersMessage() {
        StatAllocation blank = new StatAllocation("   ", CharacterClass.MAGE, 4, 4, 4);

        assertFalse(blank.hasName());
        assertEquals(GameController.NAME_MISSING, blank.problem());
        assertEquals("Enter a name for your character", blank.problem());
    }

    @Test
    void pointsNotSpentExactlyAreReportedWithTheControllersMessage() {
        StatAllocation under = new StatAllocation("Ari", CharacterClass.MAGE, 2, 3, 3);
        StatAllocation over = new StatAllocation("Ari", CharacterClass.MAGE, 4, 4, 4);

        assertEquals(GameController.STATS_INVALID, under.problem());
        assertEquals("Points left: 2", under.pointsText());
        assertEquals(GameController.STATS_INVALID, over.problem());
        assertEquals("2 points too many", over.pointsText());
        assertEquals("1 point too many", new StatAllocation("Ari", CharacterClass.MAGE, 4, 4, 3).pointsText());
    }

    @Test
    void exactlyTenPointsWithinOneToEightIsValid() {
        StatAllocation allocation = new StatAllocation("Ari", CharacterClass.WARRIOR, 8, 1, 1);

        assertTrue(allocation.isValid());
        assertNull(allocation.problem());
        assertEquals("Points left: 0", allocation.pointsText());
        assertTrue(allocation.toStats().isValid());
    }
}
