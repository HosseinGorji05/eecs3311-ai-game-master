package presentation;

import engine.character.CharacterClass;
import engine.character.StatType;
import engine.character.Stats;

import java.util.Objects;

/**
 * What the player has entered on the GUI's New Game screen (F01, SD01): a name, a class and the stat
 * points. It answers the screen's live validation (UC01 3a) without a display: how many points are left
 * and what is wrong, with the same messages the controller shows (SD01). {@link Stats#isValid()} stays
 * the rule; {@link #TOTAL_POINTS}, {@link #MIN} and {@link #MAX} only size the sliders and the counter.
 */
record StatAllocation(String name, CharacterClass characterClass, int strength, int agility, int intelligence) {

    /** The stat rules of {@link Stats} (10 points, each stat 1-8), for the sliders and the points counter. */
    static final int TOTAL_POINTS = 10;
    static final int MIN = 1;
    static final int MAX = 8;

    StatAllocation {
        Objects.requireNonNull(characterClass, "characterClass");
    }

    /** The CLI's preset points for the class, so the screen starts with a valid allocation. */
    static StatAllocation preset(String name, CharacterClass cls) {
        Stats stats = CliGameView.PRESET_STATS.get(Objects.requireNonNull(cls, "cls"));
        return new StatAllocation(name, cls, stats.modifier(StatType.STRENGTH), stats.modifier(StatType.AGILITY),
                stats.modifier(StatType.INTELLIGENCE));
    }

    Stats toStats() {
        return new Stats(strength, agility, intelligence);
    }

    /** Points still to spend; negative when too many are spent. */
    int pointsRemaining() {
        return TOTAL_POINTS - strength - agility - intelligence;
    }

    boolean hasName() {
        return name != null && !name.isBlank();
    }

    boolean isValid() {
        return problem() == null;
    }

    /** The controller's message for the first problem (blank name, then the stats), or {@code null} if none. */
    String problem() {
        if (!hasName()) {
            return GameController.NAME_MISSING;
        }
        if (!toStats().isValid()) {
            return GameController.STATS_INVALID;
        }
        return null;
    }

    /** "Points left: 2", or "1 point too many" when the sliders spend more than {@link #TOTAL_POINTS}. */
    String pointsText() {
        int left = pointsRemaining();
        if (left >= 0) {
            return "Points left: " + left;
        }
        return -left + (left == -1 ? " point" : " points") + " too many";
    }
}
