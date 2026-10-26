package engine.core;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Test double: a {@link DiceRoller} that returns a fixed sequence of d20 rolls and fails if a test
 * rolls more often than it planned, so every combat outcome in a test is chosen, not random.
 */
public class FixedDiceRoller extends DiceRoller {

    private final Deque<Integer> rolls = new ArrayDeque<>();

    public FixedDiceRoller(int... rolls) {
        super(0);
        for (int roll : rolls) {
            if (roll < 1 || roll > 20) {
                throw new IllegalArgumentException("Not a d20 roll: " + roll);
            }
            this.rolls.add(roll);
        }
    }

    @Override
    public int rollD20() {
        Integer roll = rolls.poll();
        if (roll == null) {
            throw new IllegalStateException("The test rolled more dice than it planned");
        }
        return roll;
    }

    /** How many planned rolls are left. */
    public int remaining() {
        return rolls.size();
    }
}
