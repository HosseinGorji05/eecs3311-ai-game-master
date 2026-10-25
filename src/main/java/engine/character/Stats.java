package engine.character;

import java.util.Objects;

/**
 * A character's stat allocation: strength, agility and intelligence.
 *
 * <p>Any values can be constructed so that the view can build a {@code Stats} from the player's
 * input; {@link #isValid()} then checks the rules
 * {@code strength + agility + intelligence = TOTAL_POINTS} and
 * {@code MIN <= strength, agility, intelligence <= MAX}.
 */
public class Stats {

    private static final int TOTAL_POINTS = 10;
    private static final int MIN = 1;
    private static final int MAX = 8;

    private final int strength;
    private final int agility;
    private final int intelligence;

    public Stats(int strength, int agility, int intelligence) {
        this.strength = strength;
        this.agility = agility;
        this.intelligence = intelligence;
    }

    /** Whether exactly {@code TOTAL_POINTS} are spent and every stat is within {@code MIN..MAX}. */
    public boolean isValid() {
        return inRange(strength) && inRange(agility) && inRange(intelligence)
                && strength + agility + intelligence == TOTAL_POINTS;
    }

    /** The value of the given stat, as used in attack bonuses and skill checks. */
    public int modifier(StatType stat) {
        Objects.requireNonNull(stat, "stat");
        return switch (stat) {
            case STRENGTH -> strength;
            case AGILITY -> agility;
            case INTELLIGENCE -> intelligence;
        };
    }

    private static boolean inRange(int value) {
        return value >= MIN && value <= MAX;
    }
}
