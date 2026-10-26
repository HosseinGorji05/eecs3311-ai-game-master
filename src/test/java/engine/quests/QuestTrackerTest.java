package engine.quests;

import engine.character.CharacterClass;
import engine.character.CharacterFactory;
import engine.character.Player;
import engine.character.Stats;
import engine.commands.ActionResult;
import engine.commands.GameCommand;
import engine.commands.MoveCommand;
import engine.commands.TakeCommand;
import engine.core.DiceRoller;
import engine.core.GameState;
import engine.core.RulesEngine;
import engine.events.EventType;
import engine.events.EventVerb;
import engine.events.GameEvent;
import engine.events.GameEventBus;
import engine.world.Direction;
import engine.world.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestTrackerTest {

    private GameState state;
    private GameEventBus bus;
    private RulesEngine rules;
    private Player player;

    /** Wires the game the way the controller will: the event log first, then the quest tracker. */
    @BeforeEach
    void startGame() {
        state = new GameState();
        player = CharacterFactory.forClass(CharacterClass.WARRIOR).createCharacter("Ari", new Stats(4, 3, 3));
        state.start(player, World.createDefault());
        bus = new GameEventBus();
        bus.subscribe(state.getEventLog());
        bus.subscribe(new QuestTracker(state, bus));
        rules = new RulesEngine(new DiceRoller(0), bus);
    }

    private ActionResult run(GameCommand command) {
        return rules.executeAll(List.of(command), state).get(0);
    }

    private static Quest sideQuest(String id, Reward reward, Objective... objectives) {
        return new Quest(id, "Quest " + id, "A side quest.", "blacksmith", false, List.of(objectives), reward);
    }

    private List<GameEvent> questEvents() {
        return state.getEventLog().filter(EventType.QUEST);
    }

    @Test
    void aVisitQuestCompletesAfterMovingThereAndGrantsItsReward() {
        Quest scout = sideQuest("scout", new Reward(10, 25, null), new Objective(ObjectiveType.VISIT, "forest-edge", 1));
        state.getQuestLog().accept(scout);

        assertTrue(run(new MoveCommand(Direction.SOUTH)).isSuccess());

        assertEquals(QuestStatus.COMPLETED, scout.getStatus());
        assertEquals(10, player.getGold());
        assertEquals(25, player.getXp());
        assertTrue(state.getQuestLog().activeQuests().isEmpty());
        GameEvent completed = questEvents().get(0);
        assertEquals("scout", completed.getSubjectId());
        assertEquals(EventVerb.COMPLETED, completed.getVerb());
        assertEquals(2, completed.getAlignmentDelta());
        assertEquals(1, completed.getTurn());
        assertEquals(List.of(EventType.MOVEMENT, EventType.QUEST),
                state.getEventLog().recent(10).stream().map(GameEvent::getType).toList());
    }

    @Test
    void aFetchQuestCompletesAfterTakingTheItem() {
        Quest lostKey = sideQuest("lost-key", new Reward(5, 10, null), new Objective(ObjectiveType.FETCH, "shed key", 1));
        state.getQuestLog().accept(lostKey);
        state.setCurrentLocation(state.getWorld().findLocation("tavern"));

        assertTrue(run(new TakeCommand("shed key")).isSuccess());

        assertEquals(QuestStatus.COMPLETED, lostKey.getStatus());
        assertEquals(5, player.getGold());
        assertEquals(10, player.getXp());
    }

    @Test
    void theRewardIsAppliedOnceAndACompletedQuestIsNeverCompletedAgain() {
        Quest scout = sideQuest("scout", new Reward(10, 25, null), new Objective(ObjectiveType.VISIT, "forest-edge", 1));
        state.getQuestLog().accept(scout);

        run(new MoveCommand(Direction.SOUTH));
        run(new MoveCommand(Direction.NORTH));
        run(new MoveCommand(Direction.SOUTH));
        new QuestTracker(state, bus).onEvent(new GameEvent(EventType.MOVEMENT, state.getTurn(), "moved to Forest Edge",
                "forest-edge", 0, EventVerb.MOVED));

        assertEquals(10, player.getGold());
        assertEquals(25, player.getXp());
        assertEquals(1, questEvents().size());
    }

    @Test
    void progressBuildsUpOverSeveralObjectivesAndEvents() {
        Objective visitTwice = new Objective(ObjectiveType.VISIT, "forest-edge", 2);
        Objective herbs = new Objective(ObjectiveType.FETCH, "healing herbs", 1);
        Quest herbalist = sideQuest("herbalist", new Reward(8, 12, null), visitTwice, herbs);
        state.getQuestLog().accept(herbalist);

        run(new MoveCommand(Direction.SOUTH));
        assertEquals(1, visitTwice.getProgress());
        run(new MoveCommand(Direction.EAST));          // Old Mill
        run(new TakeCommand("healing herbs"));
        assertTrue(herbs.isDone());
        assertEquals(QuestStatus.ACTIVE, herbalist.getStatus());

        run(new MoveCommand(Direction.WEST));          // back to Forest Edge

        assertEquals(QuestStatus.COMPLETED, herbalist.getStatus());
        assertEquals(8, player.getGold());
    }

    @Test
    void oneEventCanCompleteTwoQuests() {
        Quest a = sideQuest("a", new Reward(1, 1, null), new Objective(ObjectiveType.VISIT, "tavern", 1));
        Quest b = sideQuest("b", new Reward(2, 2, null), new Objective(ObjectiveType.VISIT, "tavern", 1));
        state.getQuestLog().accept(a);
        state.getQuestLog().accept(b);

        run(new MoveCommand(Direction.NORTH));

        assertEquals(QuestStatus.COMPLETED, a.getStatus());
        assertEquals(QuestStatus.COMPLETED, b.getStatus());
        assertEquals(3, player.getGold());
        assertEquals(2, questEvents().size());
    }

    @Test
    void aFailedMoveDoesNotAdvanceAVisitQuest() {
        Objective shed = new Objective(ObjectiveType.VISIT, "tool-shed", 1);
        Quest quest = sideQuest("shed", new Reward(1, 1, null), shed);
        state.getQuestLog().accept(quest);

        assertFalse(run(new MoveCommand(Direction.EAST)).isSuccess());   // locked, no key

        assertEquals(0, shed.getProgress());
        assertEquals(QuestStatus.ACTIVE, quest.getStatus());
        assertEquals(0, player.getGold());
        assertTrue(questEvents().isEmpty());
    }

    @Test
    void aQuestWhoseItemIsUsedUpFailsWithoutAReward() {
        Quest potion = sideQuest("potion", new Reward(10, 10, null),
                new Objective(ObjectiveType.FETCH, "healing potion", 1));
        state.getQuestLog().accept(potion);

        bus.publish(new GameEvent(EventType.ITEM, state.getTurn(), "used the healing potion", "healing potion", 0,
                EventVerb.USED));

        assertEquals(QuestStatus.FAILED, potion.getStatus());
        assertEquals(0, player.getGold());
        GameEvent failed = questEvents().get(0);
        assertEquals(EventVerb.FAILED, failed.getVerb());
        assertEquals(-1, failed.getAlignmentDelta());
        assertTrue(state.getQuestLog().activeQuests().isEmpty());
    }

    @Test
    void theMainQuestScoresNoAlignment() {
        Quest main = new Quest("main", "The Stolen Relic", "Recover the relic.", null, true,
                List.of(new Objective(ObjectiveType.VISIT, "forest-edge", 1)), new Reward(0, 50, null));
        Quest failingMain = new Quest("main-2", "Another Main", "Fails.", null, true,
                List.of(new Objective(ObjectiveType.FETCH, "healing potion", 1)), new Reward(0, 0, null));
        state.getQuestLog().accept(main);
        state.getQuestLog().accept(failingMain);

        run(new MoveCommand(Direction.SOUTH));
        bus.publish(new GameEvent(EventType.ITEM, state.getTurn(), "used the healing potion", "healing potion", 0,
                EventVerb.USED));

        assertEquals(List.of(0, 0), questEvents().stream().map(GameEvent::getAlignmentDelta).toList());
        assertEquals(50, player.getXp());
    }

    @Test
    void aFourthSideQuestIsRejected() {
        for (String id : List.of("a", "b", "c")) {
            assertTrue(state.getQuestLog().accept(
                    sideQuest(id, new Reward(1, 1, null), new Objective(ObjectiveType.VISIT, "wolf-den", 1))));
        }
        Quest fourth = sideQuest("d", new Reward(100, 100, null), new Objective(ObjectiveType.VISIT, "forest-edge", 1));

        assertFalse(state.getQuestLog().accept(fourth));
        run(new MoveCommand(Direction.SOUTH));

        assertEquals(QuestStatus.ACTIVE, fourth.getStatus(), "a quest that was never accepted is not tracked");
        assertEquals(0, player.getGold());
    }

    @Test
    void eventsThatMatchNothingAreIgnored() {
        Quest scout = sideQuest("scout", new Reward(10, 25, null), new Objective(ObjectiveType.VISIT, "wolf-den", 1));
        state.getQuestLog().accept(scout);

        run(new MoveCommand(Direction.NORTH));
        run(new TakeCommand("dragon egg"));

        assertEquals(0, scout.getObjectives().get(0).getProgress());
        assertEquals(QuestStatus.ACTIVE, scout.getStatus());
        assertTrue(questEvents().isEmpty());
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(NullPointerException.class, () -> new QuestTracker(null, bus));
        assertThrows(NullPointerException.class, () -> new QuestTracker(state, null));
        assertThrows(NullPointerException.class, () -> new QuestTracker(state, bus).onEvent(null));
    }
}
