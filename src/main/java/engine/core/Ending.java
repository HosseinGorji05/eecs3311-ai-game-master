package engine.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * How a game ended (F12, SD09): the ending type and the statistics, both calculated by the
 * {@link EndingCalculator}, and the epilogue written within that type by {@code EndingWriter}.
 *
 * <p>The epilogue is text to show, never something to act on; it is {@code null} until
 * {@link #setEpilogue} stores it.
 */
public class Ending {

    private final EndingType type;
    private final Map<String, Integer> statistics;
    private String epilogue;

    /** @param statistics the statistics by name, kept in the given order */
    public Ending(EndingType type, Map<String, Integer> statistics) {
        this.type = Objects.requireNonNull(type, "type");
        this.statistics = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(statistics, "statistics")));
    }

    public void setEpilogue(String text) {
        this.epilogue = Objects.requireNonNull(text, "text");
    }

    public EndingType getType() {
        return type;
    }

    /** The statistics by name (see {@link EndingCalculator#statistics}), read-only, in their order. */
    public Map<String, Integer> getStatistics() {
        return statistics;
    }

    /** One statistic, or 0 if there is none of that name. */
    public int getStatistic(String name) {
        return statistics.getOrDefault(name, 0);
    }

    /** The epilogue, or {@code null} before it is written. */
    public String getEpilogue() {
        return epilogue;
    }
}
