package presentation;

import engine.events.EventType;
import engine.events.EventVerb;
import engine.events.GameEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The event log panel's filter choices and table rows (F11, UC12), without a display. */
class EventTableModelTest {

    private static GameEvent event(EventType type, int turn, String description, int second) {
        return new GameEvent(type, turn, description, null, 0, null, Instant.parse("2026-11-05T09:00:00Z").plusSeconds(second));
    }

    @Test
    void theFiltersAreAllThenEveryRealEventType() {
        List<HistoryFilter> choices = HistoryFilter.choices();

        assertEquals(List.of("All", "Movement", "Item", "Combat", "Dialogue", "Quest", "Rejected"),
                choices.stream().map(HistoryFilter::label).toList());
        assertTrue(choices.get(0).isAll());
        assertNull(choices.get(0).type());
        assertEquals(EventType.COMBAT, choices.get(3).type());
        assertEquals("Combat", choices.get(3).toString(), "the combo box shows the label");
        assertEquals(EventType.values().length + 1, choices.size());
    }

    @Test
    void rowsShowTurnTimeTypeAndDescriptionNewestFirst() {
        List<GameEvent> log = List.of(event(EventType.MOVEMENT, 1, "moved to Tavern", 0),
                event(EventType.ITEM, 2, "took the shed key", 5),
                new GameEvent(EventType.QUEST, 2, "Quest accepted: Bread", "q1", 0, EventVerb.ACCEPTED,
                        Instant.parse("2026-11-05T09:00:07Z")));

        EventTableModel table = EventTableModel.of(log, EventTableModel.DEFAULT_CAP, ZoneOffset.UTC);

        assertEquals(List.of(new EventTableModel.Row(2, "09:00:07", "Quest", "Quest accepted: Bread"),
                new EventTableModel.Row(2, "09:00:05", "Item", "took the shed key"),
                new EventTableModel.Row(1, "09:00:00", "Movement", "moved to Tavern")), table.getRows());
        assertEquals(List.of("Turn", "Time", "Type", "Description"),
                List.of(table.getColumnName(0), table.getColumnName(1), table.getColumnName(2), table.getColumnName(3)));
        assertEquals(2, table.getValueAt(0, 0));
        assertEquals("Quest accepted: Bread", table.getValueAt(0, 3));
        assertEquals(Integer.class, table.getColumnClass(0));
        assertFalse(table.isCellEditable(0, 3));
        assertFalse(table.isCapped());
    }

    @Test
    void theCapKeepsOnlyTheMostRecentEvents() {
        List<GameEvent> log = new ArrayList<>();
        for (int i = 1; i <= 250; i++) {
            log.add(event(EventType.MOVEMENT, i, "moved " + i, i));
        }

        EventTableModel capped = EventTableModel.of(log, EventTableModel.DEFAULT_CAP, ZoneOffset.UTC);
        EventTableModel all = EventTableModel.of(log, Integer.MAX_VALUE, ZoneOffset.UTC);

        assertEquals(200, capped.getRowCount());
        assertTrue(capped.isCapped());
        assertEquals("moved 250", capped.getValueAt(0, 3));
        assertEquals("moved 51", capped.getValueAt(199, 3));
        assertEquals(250, all.getRowCount());
        assertFalse(all.isCapped());
        assertEquals("moved 1", all.getValueAt(249, 3));
    }

    @Test
    void exactlyTheCapIsNotCappedAndAnEmptyLogHasNoRows() {
        List<GameEvent> log = List.of(event(EventType.COMBAT, 1, "a", 0), event(EventType.COMBAT, 1, "b", 1));

        assertFalse(EventTableModel.of(log, 2, ZoneOffset.UTC).isCapped());
        assertTrue(EventTableModel.of(log, 1, ZoneOffset.UTC).isCapped());
        assertEquals(0, EventTableModel.of(List.of(), 5, ZoneOffset.UTC).getRowCount());
        assertEquals(0, EventTableModel.empty().getRowCount());
    }
}
