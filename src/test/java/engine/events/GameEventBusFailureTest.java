package engine.events;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One listener that throws stops neither the other listeners nor the publisher, and is reported once. */
class GameEventBusFailureTest {

    private static final class Recorder implements GameEventListener {
        final List<GameEvent> received = new ArrayList<>();

        @Override
        public void onEvent(GameEvent event) {
            received.add(event);
        }
    }

    private static final class Broken implements GameEventListener {
        int calls;

        @Override
        public void onEvent(GameEvent event) {
            calls++;
            throw new IllegalStateException("panel not ready");
        }
    }

    private final List<String> reports = new ArrayList<>();
    private final GameEventBus bus = new GameEventBus(reports::add);

    @Test
    void theListenersAfterAThrowingOneStillGetTheEventAndTheFailureIsReportedOnce() {
        Recorder first = new Recorder();
        Broken broken = new Broken();
        Recorder last = new Recorder();
        bus.subscribe(first);
        bus.subscribe(broken);
        bus.subscribe(last);
        GameEvent event = new GameEvent(EventType.MOVEMENT, 3, "moved to Tavern", "tavern", 0);

        bus.publish(event);

        assertEquals(List.of(event), first.received);
        assertEquals(List.of(event), last.received);
        assertEquals(1, broken.calls);
        assertEquals(List.of("[event bus] Broken failed on the MOVEMENT event of turn 3 (\"moved to Tavern\"): "
                + "IllegalStateException: panel not ready"), reports);
    }

    @Test
    void eachFailureIsReportedExactlyOnce() {
        bus.subscribe(new Broken());

        bus.publish(new GameEvent(EventType.ITEM, 1, "took the bread", "bread", 0));
        bus.publish(new GameEvent(EventType.ITEM, 2, "dropped the bread", "bread", 0));

        assertEquals(2, reports.size());
        assertTrue(reports.get(1).contains("turn 2"), reports.toString());
    }

    @Test
    void aFailureInANestedPublishIsReportedOnceAndTheOuterDeliveryGoesOn() {
        Recorder outerLast = new Recorder();
        Broken broken = new Broken();
        bus.subscribe(event -> {
            if (event.getType() == EventType.ITEM) {
                bus.publish(new GameEvent(EventType.QUEST, event.getTurn(), "Quest completed: Bread", "q1", 0));
            }
        });
        bus.subscribe(broken);
        bus.subscribe(outerLast);

        bus.publish(new GameEvent(EventType.ITEM, 1, "took the bread", "bread", 0));

        assertEquals(List.of(EventType.QUEST, EventType.ITEM), outerLast.received.stream().map(GameEvent::getType).toList());
        assertEquals(2, broken.calls, "once for the nested QUEST event, once for the ITEM event");
        assertEquals(2, reports.size(), reports.toString());
    }

    @Test
    void aLambdaListenerIsNamedAfterTheClassThatDefinesIt() {
        bus.subscribe(event -> {
            throw new IllegalArgumentException();
        });

        bus.publish(new GameEvent(EventType.QUEST, 1, "Quest accepted: X", "x", 0));

        assertTrue(reports.get(0).startsWith("[event bus] GameEventBusFailureTest"), reports.get(0));
        assertTrue(reports.get(0).endsWith(": IllegalArgumentException"), reports.get(0));
    }
}
