package engine.events;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameEventBusTest {

    private static GameEvent event(EventType type, String description) {
        return new GameEvent(type, 1, description, null, 0);
    }

    @Test
    void everyListenerReceivesEveryEventInPublishOrder() {
        GameEventBus bus = new GameEventBus();
        List<GameEvent> first = new ArrayList<>();
        List<GameEvent> second = new ArrayList<>();
        bus.subscribe(first::add);
        bus.subscribe(second::add);
        GameEvent moved = event(EventType.MOVEMENT, "moved to Tavern");
        GameEvent took = event(EventType.ITEM, "took the shed key");

        bus.publish(moved);
        bus.publish(took);

        assertEquals(List.of(moved, took), first);
        assertEquals(List.of(moved, took), second);
    }

    @Test
    void listenersAreNotifiedInTheOrderTheySubscribed() {
        GameEventBus bus = new GameEventBus();
        List<String> calls = new ArrayList<>();
        bus.subscribe(e -> calls.add("log"));
        bus.subscribe(e -> calls.add("tracker"));
        bus.subscribe(e -> calls.add("view"));

        bus.publish(event(EventType.ITEM, "took the shed key"));

        assertEquals(List.of("log", "tracker", "view"), calls);
    }

    @Test
    void anUnsubscribedListenerGetsNothing() {
        GameEventBus bus = new GameEventBus();
        List<GameEvent> kept = new ArrayList<>();
        List<GameEvent> removed = new ArrayList<>();
        GameEventListener removedListener = removed::add;
        bus.subscribe(kept::add);
        bus.subscribe(removedListener);

        bus.unsubscribe(removedListener);
        bus.publish(event(EventType.MOVEMENT, "moved to Tavern"));

        assertEquals(1, kept.size());
        assertTrue(removed.isEmpty());
    }

    @Test
    void subscribingTwiceDeliversOnceAndUnsubscribingAStrangerIsHarmless() {
        GameEventBus bus = new GameEventBus();
        List<GameEvent> received = new ArrayList<>();
        GameEventListener listener = received::add;
        bus.subscribe(listener);
        bus.subscribe(listener);
        bus.unsubscribe(e -> { });

        bus.publish(event(EventType.MOVEMENT, "moved to Tavern"));

        assertEquals(1, received.size());
    }

    @Test
    void publishingWithoutListenersDoesNothing() {
        new GameEventBus().publish(event(EventType.QUEST, "Quest completed: Lost Key"));
    }

    @Test
    void aListenerCanPublishDuringDeliveryAndEveryoneReceivesBothEvents() {
        GameEventBus bus = new GameEventBus();
        List<GameEvent> log = new ArrayList<>();
        List<GameEvent> view = new ArrayList<>();
        GameEvent took = event(EventType.ITEM, "took the shed key");
        GameEvent completed = event(EventType.QUEST, "Quest completed: Lost Key");
        bus.subscribe(log::add);
        bus.subscribe(e -> {   // like the QuestTracker: reacts to the ITEM event with a QUEST event
            if (e == took) {
                bus.publish(completed);
            }
        });
        bus.subscribe(view::add);

        bus.publish(took);

        // Delivery is synchronous (SD05): the nested QUEST event reaches every listener at once,
        // so a listener subscribed after the publisher sees it before the rest of the ITEM delivery.
        assertEquals(List.of(took, completed), log);
        assertEquals(List.of(completed, took), view);
    }

    @Test
    void subscribingDuringDeliveryTakesEffectFromTheNextEvent() {
        GameEventBus bus = new GameEventBus();
        List<GameEvent> late = new ArrayList<>();
        GameEventListener lateListener = late::add;
        bus.subscribe(e -> bus.subscribe(lateListener));
        GameEvent firstEvent = event(EventType.MOVEMENT, "moved to Tavern");
        GameEvent secondEvent = event(EventType.MOVEMENT, "moved to Village Square");

        bus.publish(firstEvent);
        bus.publish(secondEvent);

        assertEquals(List.of(secondEvent), late);
    }

    @Test
    void nullIsRejected() {
        GameEventBus bus = new GameEventBus();
        assertThrows(NullPointerException.class, () -> bus.subscribe(null));
        assertThrows(NullPointerException.class, () -> bus.publish(null));
    }
}
