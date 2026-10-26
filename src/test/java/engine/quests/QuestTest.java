package engine.quests;

import engine.character.CharacterClass;
import engine.character.CharacterFactory;
import engine.character.Player;
import engine.character.Stats;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestTest {

    private static Objective visit(String locationId) {
        return new Objective(ObjectiveType.VISIT, locationId, 1);
    }

    private static Quest quest(List<Objective> objectives) {
        return new Quest("q1", "Scout the Forest", "Look around the forest.", "hunter", false, objectives,
                new Reward(10, 5, "hunting knife"));
    }

    @Test
    void newQuestIsActiveAndKeepsItsFields() {
        Quest quest = quest(List.of(visit("forest-edge")));

        assertEquals(QuestStatus.ACTIVE, quest.getStatus());
        assertEquals("q1", quest.getId());
        assertEquals("Scout the Forest", quest.getTitle());
        assertEquals("Look around the forest.", quest.getDescription());
        assertEquals("hunter", quest.getGiverId());
        assertFalse(quest.isMain());
        assertEquals("hunting knife", quest.getReward().getItemName());
    }

    @Test
    void isCompleteWhenEveryObjectiveIsDone() {
        Objective first = visit("forest-edge");
        Objective second = visit("wolf-den");
        Quest quest = quest(List.of(first, second));

        first.advance();
        assertFalse(quest.isComplete());

        second.advance();
        assertTrue(quest.isComplete());
        assertEquals(QuestStatus.ACTIVE, quest.getStatus(), "isComplete() does not finish the quest");
    }

    @Test
    void aQuestIsCompletedOrFailedOnlyOnce() {
        Quest completed = quest(List.of(visit("forest-edge")));
        completed.complete();
        assertEquals(QuestStatus.COMPLETED, completed.getStatus());
        assertThrows(IllegalStateException.class, completed::complete);
        assertThrows(IllegalStateException.class, completed::fail);

        Quest failed = quest(List.of(visit("forest-edge")));
        failed.fail();
        assertEquals(QuestStatus.FAILED, failed.getStatus());
        assertThrows(IllegalStateException.class, failed::complete);
    }

    @Test
    void aQuestHasOneToThreeObjectives() {
        assertThrows(IllegalArgumentException.class, () -> quest(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> quest(List.of(visit("a"), visit("b"), visit("c"), visit("d"))));
        assertEquals(3, quest(List.of(visit("a"), visit("b"), visit("c"))).getObjectives().size());
        assertThrows(UnsupportedOperationException.class,
                () -> quest(List.of(visit("a"))).getObjectives().add(visit("b")));
    }

    @Test
    void invalidQuestsAreRejected() {
        List<Objective> one = List.of(visit("a"));
        Reward reward = new Reward(0, 0, null);
        assertThrows(IllegalArgumentException.class, () -> new Quest(" ", "T", "D", null, false, one, reward));
        assertThrows(IllegalArgumentException.class, () -> new Quest("id", "", "D", null, false, one, reward));
        assertThrows(NullPointerException.class, () -> new Quest("id", "T", null, null, false, one, reward));
        assertThrows(NullPointerException.class, () -> new Quest("id", "T", "D", null, false, null, reward));
        assertThrows(NullPointerException.class, () -> new Quest("id", "T", "D", null, false, one, null));
    }

    @Test
    void addRewardAddsGoldAndXpToThePlayer() {
        Player player = CharacterFactory.forClass(CharacterClass.MAGE).createCharacter("Ari", new Stats(2, 3, 5));
        assertEquals(0, player.getGold());
        assertEquals(0, player.getXp());

        player.addReward(new Reward(15, 20, "hunting knife"));
        player.addReward(new Reward(5, 0, null));

        assertEquals(20, player.getGold());
        assertEquals(20, player.getXp());
        assertNull(player.getInventory().find("hunting knife"), "the reward item is handed over by its giver later");
        assertThrows(NullPointerException.class, () -> player.addReward(null));
    }

    @Test
    void invalidRewardsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Reward(-1, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new Reward(0, -1, null));
        assertThrows(IllegalArgumentException.class, () -> new Reward(0, 0, " "));
        Reward reward = new Reward(3, 4, null);
        assertEquals(3, reward.getGold());
        assertEquals(4, reward.getXp());
        assertNull(reward.getItemName());
    }
}
