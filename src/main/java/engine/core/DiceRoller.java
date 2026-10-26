package engine.core;

import java.util.Random;

/**
 * Rolls the dice that decide combat and skill checks. The same seed always gives the same rolls,
 * so tests and replays are reproducible.
 */
public class DiceRoller {

    private final Random random;

    public DiceRoller(long seed) {
        this.random = new Random(seed);
    }

    /** A roll of a twenty-sided die: 1 to 20, each equally likely. */
    public int rollD20() {
        return random.nextInt(20) + 1;
    }
}
