package engine.character;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatsTest {

    @ParameterizedTest(name = "{0}/{1}/{2} is valid")
    @CsvSource({
            "4, 3, 3",
            "3, 4, 3",
            "1, 1, 8",
            "8, 1, 1",
            "1, 8, 1",
            "2, 5, 3"
    })
    void allocationsOfExactlyTenPointsWithinLimitsAreValid(int str, int agi, int intel) {
        assertTrue(new Stats(str, agi, intel).isValid());
    }

    @ParameterizedTest(name = "total {0}+{1}+{2} is invalid")
    @CsvSource({
            "3, 3, 3",   // 9 points: one left unspent
            "4, 4, 3",   // 11 points: one too many
            "1, 1, 1",
            "8, 8, 8"
    })
    void allocationsThatDoNotSpendExactlyTenPointsAreInvalid(int str, int agi, int intel) {
        assertFalse(new Stats(str, agi, intel).isValid());
    }

    @ParameterizedTest(name = "{0}/{1}/{2} breaks the 1-8 limit")
    @CsvSource({
            "0, 5, 5",   // below MIN although the total is 10
            "5, 0, 5",
            "5, 5, 0",
            "9, 1, 0",   // above MAX
            "-1, 3, 8",
            "1, 0, 9"
    })
    void statsOutsideOneToEightAreInvalidEvenWhenTheTotalIsTen(int str, int agi, int intel) {
        assertEquals(10, str + agi + intel);
        assertFalse(new Stats(str, agi, intel).isValid());
    }

    @Test
    void minimumOfOneAndMaximumOfEightAreBothAllowed() {
        assertTrue(new Stats(1, 1, 8).isValid());
        assertTrue(new Stats(8, 1, 1).isValid());
        assertFalse(new Stats(0, 2, 8).isValid());
        assertFalse(new Stats(9, 0, 1).isValid());
    }

    @Test
    void modifierReturnsTheValueOfTheRequestedStat() {
        Stats stats = new Stats(2, 5, 3);
        assertEquals(2, stats.modifier(StatType.STRENGTH));
        assertEquals(5, stats.modifier(StatType.AGILITY));
        assertEquals(3, stats.modifier(StatType.INTELLIGENCE));
    }

    @Test
    void modifierRejectsNull() {
        assertThrows(NullPointerException.class, () -> new Stats(4, 3, 3).modifier(null));
    }
}
