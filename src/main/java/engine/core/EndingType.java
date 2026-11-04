package engine.core;

/**
 * The four kinds of ending (F12). DEATH when the player died; otherwise the alignment score picks
 * HEROIC, NEUTRAL or DARK ({@link EndingCalculator#determineType}).
 */
public enum EndingType {
    HEROIC,
    NEUTRAL,
    DARK,
    DEATH
}
