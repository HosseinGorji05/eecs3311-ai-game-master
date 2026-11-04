package presentation;

import engine.quests.Objective;
import engine.quests.ObjectiveType;
import engine.quests.Quest;
import engine.quests.QuestLog;
import engine.quests.Reward;
import engine.world.World;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The quest log panel's rows (F08), without a display. */
class QuestListModelTest {

    private final World world = World.createDefault();

    private static Quest main() {
        return new Quest("main-quest", "Recover the village relic", "Bandits stole it.", null, true,
                List.of(new Objective(ObjectiveType.DEFEAT, "bandit leader", 1),
                        new Objective(ObjectiveType.FETCH, "village relic", 1)),
                new Reward(50, 50, null));
    }

    private static Quest side(String id, String title) {
        return new Quest(id, title, "Bring it back.", "blacksmith", false,
                List.of(new Objective(ObjectiveType.FETCH, "bread", 1)), new Reward(15, 15, "iron key"));
    }

    @Test
    void anEntryShowsTheStatusGiverObjectiveProgressAndReward() {
        QuestListModel.Entry entry = QuestListModel.entry(side("q1", "Bread for Brann"), world);

        assertEquals("Bread for Brann", entry.title());
        assertEquals("active, from Brann", entry.status());
        assertEquals(List.of("[ ] fetch bread 0/1"), entry.objectives());
        assertEquals("15 gold, 15 XP, the iron key", entry.reward());
    }

    @Test
    void theMainQuestHasNoGiverAndADoneObjectiveIsTicked() {
        Quest main = main();
        main.getObjectives().get(0).advance();

        QuestListModel.Entry entry = QuestListModel.entry(main, world);

        assertEquals("main quest, active", entry.status());
        assertEquals(List.of("[x] defeat bandit leader 1/1", "[ ] fetch village relic 0/1"), entry.objectives());
    }

    @Test
    void activeQuestsComeFirstWithTheMainQuestOnTopThenCompletedThenFailed() {
        QuestLog log = new QuestLog();
        Quest done = side("q1", "Done");
        Quest failed = side("q2", "Failed");
        Quest open = side("q3", "Open");
        Quest main = main();
        log.restore(List.of(done, failed, open, main));
        done.complete();
        failed.fail();

        QuestListModel model = QuestListModel.of(log, world);

        assertEquals(List.of(main, open, done, failed), model.getQuests());
        assertEquals("completed, from Brann", model.entryOf(done).status());
        assertEquals("failed, from Brann", model.entryOf(failed).status());
    }

    @Test
    void theEntriesAreACopyOfTheProgressWhenTaken() {
        QuestLog log = new QuestLog();
        Quest quest = side("q1", "Bread for Brann");
        log.accept(quest);
        QuestListModel before = QuestListModel.of(log, world);

        quest.getObjectives().get(0).advance();

        assertEquals(List.of("[ ] fetch bread 0/1"), before.entryOf(quest).objectives());
        assertEquals(List.of("[x] fetch bread 1/1"), QuestListModel.of(log, world).entryOf(quest).objectives());
        assertNull(before.entryOf(main()), "only the copied quests have entries");
    }

    @Test
    void theHtmlEscapesTextFromTheModel() {
        String html = QuestListModel.entry(side("q1", "<b>Free</b> & \"easy\""), world).toHtml();

        assertTrue(html.startsWith("<html><b>&lt;b&gt;Free&lt;/b&gt; &amp; &quot;easy&quot;</b>"), html);
        assertFalse(html.contains("<b>Free"), html);
        assertTrue(html.contains("[ ] fetch bread 0/1"), html);
    }
}
