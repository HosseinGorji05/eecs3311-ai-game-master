package engine.quests;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestLogTest {

    private static Quest sideQuest(String id) {
        return new Quest(id, "Quest " + id, "A side quest.", "blacksmith", false,
                List.of(new Objective(ObjectiveType.VISIT, "wolf-den", 1)), new Reward(10, 5, null));
    }

    private static Quest mainQuest() {
        return new Quest("main", "The Stolen Relic", "Recover the village relic.", null, true,
                List.of(new Objective(ObjectiveType.VISIT, "bandit-camp", 1)), new Reward(0, 50, null));
    }

    @Test
    void acceptedQuestsAreActiveInOrder() {
        QuestLog log = new QuestLog();
        Quest a = sideQuest("a");
        Quest main = mainQuest();

        assertTrue(log.accept(a));
        assertTrue(log.accept(main));

        assertEquals(List.of(a, main), log.activeQuests());
        assertEquals(1, log.activeSideQuestCount());
    }

    @Test
    void aFourthActiveSideQuestIsRejected() {
        QuestLog log = new QuestLog();
        assertTrue(log.accept(sideQuest("a")));
        assertTrue(log.accept(sideQuest("b")));
        assertTrue(log.accept(sideQuest("c")));
        Quest fourth = sideQuest("d");

        assertFalse(log.accept(fourth));

        assertEquals(3, log.activeSideQuestCount());
        assertFalse(log.activeQuests().contains(fourth));
    }

    @Test
    void theMainQuestDoesNotCountTowardsTheSideQuestLimit() {
        QuestLog log = new QuestLog();
        log.accept(sideQuest("a"));
        log.accept(sideQuest("b"));
        log.accept(sideQuest("c"));

        assertTrue(log.accept(mainQuest()));
        assertEquals(3, log.activeSideQuestCount());
        assertEquals(4, log.activeQuests().size());
    }

    @Test
    void finishingASideQuestFreesASlot() {
        QuestLog log = new QuestLog();
        Quest a = sideQuest("a");
        Quest b = sideQuest("b");
        log.accept(a);
        log.accept(b);
        log.accept(sideQuest("c"));

        a.complete();
        b.fail();

        assertEquals(1, log.activeSideQuestCount());
        assertEquals(1, log.activeQuests().size());
        assertTrue(log.accept(sideQuest("d")));
    }

    @Test
    void aQuestIsAcceptedOnlyOnceAndOnlyWhileActive() {
        QuestLog log = new QuestLog();
        Quest a = sideQuest("a");
        Quest finished = sideQuest("b");
        finished.complete();

        assertTrue(log.accept(a));
        assertFalse(log.accept(a));
        assertFalse(log.accept(finished));
        assertThrows(NullPointerException.class, () -> log.accept(null));
        assertEquals(1, log.activeQuests().size());
    }

    @Test
    void activeQuestsCannotBeModifiedFromOutside() {
        QuestLog log = new QuestLog();
        log.accept(sideQuest("a"));
        assertThrows(UnsupportedOperationException.class, () -> log.activeQuests().add(sideQuest("b")));
    }
}
