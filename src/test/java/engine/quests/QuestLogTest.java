package engine.quests;

import engine.world.NPC;

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


    private static final NPC BRANN = new NPC("blacksmith", "Brann", "blacksmith", "gruff", List.of(), List.of(), true);
    private static final NPC MARTA = new NPC("tavern-keeper", "Marta", "tavern keeper", "warm", List.of(), List.of(), false);

    @Test
    void hasQuestFromCountsOnlyActiveQuestsFromThatGiver() {
        QuestLog log = new QuestLog();
        assertFalse(log.hasQuestFrom(BRANN));

        Quest quest = sideQuest("a");
        log.accept(quest);
        assertTrue(log.hasQuestFrom(BRANN));
        assertFalse(log.hasQuestFrom(MARTA));

        quest.complete();
        assertFalse(log.hasQuestFrom(BRANN), "a finished quest is no open quest");
    }

    @Test
    void canOfferFromNeedsAQuestGiverWithoutAnOpenQuestAndRoomInTheLog() {
        QuestLog log = new QuestLog();
        assertTrue(log.canOfferFrom(BRANN));
        assertFalse(log.canOfferFrom(MARTA), "Marta gives no quests");

        log.accept(sideQuest("a"));
        assertFalse(log.canOfferFrom(BRANN), "Brann's quest is still open");

        QuestLog full = new QuestLog();
        for (String id : List.of("x", "y", "z")) {
            full.accept(new Quest(id, "Quest " + id, "Someone else's.", "someone", false,
                    List.of(new Objective(ObjectiveType.VISIT, "wolf-den", 1)), new Reward(1, 1, null)));
        }
        assertFalse(full.canOfferFrom(BRANN), "three side quests are active");
        full.activeQuests().get(0).fail();
        assertTrue(full.canOfferFrom(BRANN));
    }

    @Test
    void allQuestsListsActiveAndFinishedQuestsInOrder() {
        QuestLog log = new QuestLog();
        Quest a = sideQuest("a");
        Quest b = sideQuest("b");
        log.accept(a);
        log.accept(b);
        a.complete();

        assertEquals(List.of(a, b), log.allQuests());
        assertEquals(List.of(b), log.activeQuests());
        assertThrows(UnsupportedOperationException.class, () -> log.allQuests().clear());
        assertThrows(NullPointerException.class, () -> log.hasQuestFrom(null));
    }

    @Test
    void restoreReplacesTheLogWithFinishedQuestsAsTheyAre() {
        QuestLog log = new QuestLog();
        log.accept(sideQuest("old"));
        Quest done = sideQuest("done");
        done.complete();
        Quest active = sideQuest("active");

        log.restore(List.of(done, active));

        assertEquals(List.of(done, active), log.allQuests());
        assertEquals(List.of(active), log.activeQuests());
    }

    @Test
    void restoreRefusesDuplicatesAndTooManyActiveSideQuestsAndThenChangesNothing() {
        QuestLog log = new QuestLog();
        Quest kept = sideQuest("kept");
        log.accept(kept);
        Quest a = sideQuest("a");

        assertThrows(IllegalArgumentException.class, () -> log.restore(List.of(a, a)));
        assertThrows(IllegalArgumentException.class, () -> log.restore(List.of(a, sideQuest("a"))));
        assertThrows(IllegalArgumentException.class, () -> log.restore(
                List.of(sideQuest("1"), sideQuest("2"), sideQuest("3"), sideQuest("4"))));
        assertEquals(List.of(kept), log.allQuests());
        log.restore(List.of(sideQuest("1"), sideQuest("2"), sideQuest("3"), mainQuest()));
        assertEquals(4, log.activeQuests().size(), "the main quest does not count");
    }
}
