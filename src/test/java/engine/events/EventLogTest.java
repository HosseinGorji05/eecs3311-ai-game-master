package engine.events;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventLogTest {

    private static final GameEvent MOVED = new GameEvent(EventType.MOVEMENT, 1, "moved to Tavern", "tavern", 0,
            EventVerb.MOVED);
    private static final GameEvent TOOK = new GameEvent(EventType.ITEM, 2, "took the shed key", "shed key", 0,
            EventVerb.TOOK);
    private static final GameEvent REJECTED = new GameEvent(EventType.REJECTED, 2,
            "Rejected TAKE: There is no dragon egg here.", "TAKE", 0);
    private static final GameEvent STOLE = new GameEvent(EventType.ITEM, 3, "stole the iron key from blacksmith",
            "iron key", -2, EventVerb.TOOK);

    private static EventLog logWithFourEvents() {
        EventLog log = new EventLog();
        GameEventBus bus = new GameEventBus();
        bus.subscribe(log);
        bus.publish(MOVED);
        bus.publish(TOOK);
        bus.publish(REJECTED);
        bus.publish(STOLE);
        return log;
    }

    @Test
    void eventsAreKeptInOrderWithTheirTurnNumbers() {
        List<GameEvent> all = logWithFourEvents().recent(Integer.MAX_VALUE);

        assertEquals(List.of(MOVED, TOOK, REJECTED, STOLE), all);
        assertEquals(List.of(1, 2, 2, 3), all.stream().map(GameEvent::getTurn).toList());
    }

    @Test
    void filterReturnsOnlyEventsOfThatTypeInOrder() {
        EventLog log = logWithFourEvents();

        assertEquals(List.of(TOOK, STOLE), log.filter(EventType.ITEM));
        assertEquals(List.of(REJECTED), log.filter(EventType.REJECTED));
        assertTrue(log.filter(EventType.COMBAT).isEmpty());
    }

    @Test
    void recentReturnsTheLastEventsOldestFirst() {
        EventLog log = logWithFourEvents();

        assertEquals(List.of(REJECTED, STOLE), log.recent(2));
        assertEquals(List.of(MOVED, TOOK, REJECTED, STOLE), log.recent(20));
        assertTrue(log.recent(0).isEmpty());
        assertTrue(new EventLog().recent(20).isEmpty());
    }

    @Test
    void queriesReturnSnapshotsThatCannotChangeTheLog() {
        EventLog log = logWithFourEvents();
        List<GameEvent> recent = log.recent(2);
        List<GameEvent> items = log.filter(EventType.ITEM);

        log.onEvent(new GameEvent(EventType.ITEM, 4, "took the bread", "bread", 0, EventVerb.TOOK));

        assertEquals(2, recent.size());
        assertEquals(2, items.size());
        assertThrows(UnsupportedOperationException.class, () -> recent.add(MOVED));
        assertThrows(UnsupportedOperationException.class, () -> items.add(MOVED));
        assertEquals(3, log.filter(EventType.ITEM).size());
    }

    @Test
    void invalidQueriesAreRejected() {
        EventLog log = new EventLog();
        assertThrows(IllegalArgumentException.class, () -> log.recent(-1));
        assertThrows(NullPointerException.class, () -> log.filter(null));
        assertThrows(NullPointerException.class, () -> log.onEvent(null));
    }

    @Test
    void eventsKeepAllTheirFields() {
        assertEquals(EventType.ITEM, STOLE.getType());
        assertEquals(3, STOLE.getTurn());
        assertEquals("stole the iron key from blacksmith", STOLE.getDescription());
        assertEquals("iron key", STOLE.getSubjectId());
        assertEquals(-2, STOLE.getAlignmentDelta());
        assertEquals(EventVerb.TOOK, STOLE.getVerb());
        assertNotNull(STOLE.getTimestamp());
        assertNull(REJECTED.getVerb());
    }

    @Test
    void invalidEventsAreRejected() {
        assertThrows(NullPointerException.class, () -> new GameEvent(null, 1, "x", null, 0));
        assertThrows(IllegalArgumentException.class, () -> new GameEvent(EventType.ITEM, -1, "x", null, 0));
        assertThrows(IllegalArgumentException.class, () -> new GameEvent(EventType.ITEM, 1, " ", null, 0));
        assertThrows(NullPointerException.class, () -> new GameEvent(EventType.ITEM, 1, null, null, 0));
    }
}
