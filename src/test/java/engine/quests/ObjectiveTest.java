package engine.quests;

import engine.events.EventType;
import engine.events.EventVerb;
import engine.events.GameEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectiveTest {

    private static GameEvent event(EventType type, String subjectId, EventVerb verb) {
        return new GameEvent(type, 1, "something happened", subjectId, 0, verb);
    }

    @Test
    void fetchMatchesTakingTheTargetItemIgnoringCase() {
        Objective fetch = new Objective(ObjectiveType.FETCH, "iron key", 1);

        assertTrue(fetch.matches(event(EventType.ITEM, "Iron Key", EventVerb.TOOK)));
        assertFalse(fetch.matches(event(EventType.ITEM, "shed key", EventVerb.TOOK)));
        assertFalse(fetch.matches(event(EventType.ITEM, "iron key", EventVerb.USED)));
        assertFalse(fetch.matches(event(EventType.ITEM, "iron key", null)));
        assertFalse(fetch.matches(event(EventType.MOVEMENT, "iron key", EventVerb.MOVED)));
        assertFalse(fetch.matches(event(EventType.ITEM, null, EventVerb.TOOK)));
    }

    @Test
    void visitMatchesMovingIntoTheTargetLocation() {
        Objective visit = new Objective(ObjectiveType.VISIT, "wolf-den", 1);

        assertTrue(visit.matches(event(EventType.MOVEMENT, "wolf-den", EventVerb.MOVED)));
        assertFalse(visit.matches(event(EventType.MOVEMENT, "tavern", EventVerb.MOVED)));
        assertFalse(visit.matches(event(EventType.REJECTED, "wolf-den", null)));
    }

    @Test
    void defeatMatchesOnlyTheDefeatOfTheTargetEnemy() {
        Objective defeat = new Objective(ObjectiveType.DEFEAT, "wolf", 1);

        assertTrue(defeat.matches(event(EventType.COMBAT, "wolf", EventVerb.DEFEATED)));
        assertFalse(defeat.matches(event(EventType.COMBAT, "wolf", null)), "an attack roll is not a defeat");
        assertFalse(defeat.matches(event(EventType.COMBAT, "bandit", EventVerb.DEFEATED)));
    }

    @Test
    void progressAdvancesUpToTheRequiredCountAndThenStopsMatching() {
        Objective visit = new Objective(ObjectiveType.VISIT, "forest-edge", 2);
        GameEvent moved = event(EventType.MOVEMENT, "forest-edge", EventVerb.MOVED);

        visit.advance();
        assertEquals(1, visit.getProgress());
        assertFalse(visit.isDone());
        assertTrue(visit.matches(moved));

        visit.advance();
        assertTrue(visit.isDone());
        assertFalse(visit.matches(moved));

        visit.advance();
        assertEquals(2, visit.getProgress());
    }

    @Test
    void fetchIsBlockedWhenItsItemIsUsedUp() {
        Objective fetch = new Objective(ObjectiveType.FETCH, "healing potion", 1);

        assertTrue(fetch.isBlockedBy(event(EventType.ITEM, "Healing Potion", EventVerb.USED)));
        assertFalse(fetch.isBlockedBy(event(EventType.ITEM, "healing potion", EventVerb.TOOK)));
        assertFalse(fetch.isBlockedBy(event(EventType.ITEM, "bread", EventVerb.USED)));
    }

    @Test
    void visitAndDefeatAreNeverBlocked() {
        GameEvent used = event(EventType.ITEM, "wolf", EventVerb.USED);
        assertFalse(new Objective(ObjectiveType.VISIT, "wolf", 1).isBlockedBy(used));
        assertFalse(new Objective(ObjectiveType.DEFEAT, "wolf", 1).isBlockedBy(used));
    }

    @Test
    void invalidObjectivesAreRejected() {
        assertThrows(NullPointerException.class, () -> new Objective(null, "x", 1));
        assertThrows(NullPointerException.class, () -> new Objective(ObjectiveType.FETCH, null, 1));
        assertThrows(IllegalArgumentException.class, () -> new Objective(ObjectiveType.FETCH, " ", 1));
        assertThrows(IllegalArgumentException.class, () -> new Objective(ObjectiveType.FETCH, "x", 0));
    }
}
