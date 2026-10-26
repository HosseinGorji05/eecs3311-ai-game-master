package engine.core;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiceRollerTest {

    private static int[] roll(DiceRoller dice, int count) {
        return IntStream.range(0, count).map(i -> dice.rollD20()).toArray();
    }

    @Test
    void theSameSeedGivesTheSameRolls() {
        assertEquals(Arrays.toString(roll(new DiceRoller(42), 50)), Arrays.toString(roll(new DiceRoller(42), 50)));
    }

    @Test
    void differentSeedsGiveDifferentRolls() {
        assertNotEquals(Arrays.toString(roll(new DiceRoller(1), 50)), Arrays.toString(roll(new DiceRoller(2), 50)));
    }

    @Test
    void everyRollIsBetweenOneAndTwentyAndEveryFaceComesUp() {
        Set<Integer> faces = new HashSet<>();
        for (int roll : roll(new DiceRoller(7), 2000)) {
            assertTrue(roll >= 1 && roll <= 20, "rolled " + roll);
            faces.add(roll);
        }
        assertEquals(20, faces.size());
    }

    @Test
    void theFixedTestDiceReturnTheirSequenceAndRefuseExtraRolls() {
        FixedDiceRoller dice = new FixedDiceRoller(20, 1, 12);

        assertEquals(20, dice.rollD20());
        assertEquals(1, dice.rollD20());
        assertEquals(1, dice.remaining());
        assertEquals(12, dice.rollD20());
        assertThrows(IllegalStateException.class, dice::rollD20);
        assertThrows(IllegalArgumentException.class, () -> new FixedDiceRoller(21));
    }
}
