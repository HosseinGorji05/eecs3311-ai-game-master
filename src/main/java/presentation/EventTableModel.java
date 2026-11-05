package presentation;

import engine.events.GameEvent;

import javax.swing.table.AbstractTableModel;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The rows of the event log panel (F11, UC12): turn, time, type and description of each event, newest
 * first (UC12 1a). When there are more than the cap, only the most recent are kept and
 * {@link #isCapped()} says so; the panel's "Show all" lifts the cap.
 *
 * <p>The rows are copies of the events' values, so the table can be shown on the Swing thread while the
 * game goes on. Cells cannot be edited.
 */
final class EventTableModel extends AbstractTableModel {

    /** How many of the most recent events the panel shows unless "Show all" is on. */
    static final int DEFAULT_CAP = 200;
    static final List<String> COLUMNS = List.of("Turn", "Time", "Type", "Description");

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    /** One shown event. */
    record Row(int turn, String time, String type, String description) {
    }

    private final List<Row> rows;
    private final boolean capped;

    private EventTableModel(List<Row> rows, boolean capped) {
        this.rows = List.copyOf(rows);
        this.capped = capped;
    }

    static EventTableModel empty() {
        return new EventTableModel(List.of(), false);
    }

    /**
     * The table for these events (oldest first, as the event log keeps them): the last {@code cap} of them,
     * newest first, with the time in the given zone.
     *
     * @param cap the most rows to keep; {@link Integer#MAX_VALUE} for all
     */
    static EventTableModel of(List<GameEvent> events, int cap, ZoneId zone) {
        Objects.requireNonNull(events, "events");
        if (cap < 0) {
            throw new IllegalArgumentException("Cap must not be negative: " + cap);
        }
        DateTimeFormatter time = TIME.withZone(zone);
        int first = Math.max(0, events.size() - cap);
        List<Row> rows = new ArrayList<>();
        for (int index = events.size() - 1; index >= first; index--) {
            GameEvent event = events.get(index);
            rows.add(new Row(event.getTurn(), time.format(event.getTimestamp()),
                    new HistoryFilter(event.getType()).label(), event.getDescription()));
        }
        return new EventTableModel(rows, first > 0);
    }

    /** Whether older events were left out. */
    boolean isCapped() {
        return capped;
    }

    List<Row> getRows() {
        return rows;
    }

    @Override
    public int getRowCount() {
        return rows.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.size();
    }

    @Override
    public String getColumnName(int column) {
        return COLUMNS.get(column);
    }

    @Override
    public Class<?> getColumnClass(int column) {
        return column == 0 ? Integer.class : String.class;
    }

    @Override
    public Object getValueAt(int row, int column) {
        Row shown = rows.get(row);
        return switch (column) {
            case 0 -> shown.turn();
            case 1 -> shown.time();
            case 2 -> shown.type();
            case 3 -> shown.description();
            default -> throw new IndexOutOfBoundsException("No column " + column);
        };
    }

    @Override
    public boolean isCellEditable(int row, int column) {
        return false;
    }
}
