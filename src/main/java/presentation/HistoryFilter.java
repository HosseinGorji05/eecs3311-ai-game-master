package presentation;

import engine.events.EventType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A choice of the event log panel's filter (F11, UC12): All, or one real {@link EventType} (the design
 * names combat, quest, dialogue and movement; the engine also has item and rejected events, so those are
 * offered too). All is shown with {@code GameController.getRecentHistory}, a type with
 * {@code getHistory(type)} (SD06).
 */
record HistoryFilter(EventType type) {

    static final HistoryFilter ALL = new HistoryFilter(null);

    /** All first, then every event type in the engine's order. */
    static List<HistoryFilter> choices() {
        List<HistoryFilter> choices = new ArrayList<>();
        choices.add(ALL);
        for (EventType type : EventType.values()) {
            choices.add(new HistoryFilter(type));
        }
        return choices;
    }

    boolean isAll() {
        return type == null;
    }

    /** "All", "Combat", "Movement". */
    String label() {
        if (type == null) {
            return "All";
        }
        String name = type.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /** The label, so a combo box shows it. */
    @Override
    public String toString() {
        return label();
    }
}
