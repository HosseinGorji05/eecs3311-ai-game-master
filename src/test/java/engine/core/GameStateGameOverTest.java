package engine.core;

import engine.character.CharacterClass;
import engine.character.CharacterFactory;
import engine.character.Player;
import engine.character.Stats;
import engine.commands.AttackCommand;
import engine.commands.EquipCommand;
import engine.commands.GameCommand;
import engine.commands.MoveCommand;
import engine.commands.TakeCommand;
import engine.events.GameEvent;
import engine.events.EventType;
import engine.events.GameEventBus;
import engine.quests.Objective;
import engine.quests.ObjectiveType;
import engine.quests.Quest;
import engine.quests.QuestStatus;
import engine.quests.QuestTracker;
import engine.quests.Reward;
import engine.world.Direction;
import engine.world.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GameState.isGameOver()} (F12, SD09 n1): Ari the warrior recovers the village relic from the
 * bandit leader through the real rules engine and quest tracker, or dies.
 */
class GameStateGameOverTest {

    private GameState state;
    private RulesEngine rules;
    private Quest main;

    @BeforeEach
    void startGame() {
        state = new GameState();
        state.start(CharacterFactory.forClass(CharacterClass.WARRIOR).createCharacter("Ari", new Stats(5, 3, 2)),
                World.createDefault());
        GameEventBus bus = new GameEventBus();
        bus.subscribe(state.getEventLog());
        bus.subscribe(new QuestTracker(state, bus));
        rules = new RulesEngine(new FixedDiceRoller(20, 1, 20), bus);   // Ari hits, the leader misses, Ari hits
        main = new Quest("main-quest", "Recover the village relic", "Take the relic back.", null, true,
                List.of(new Objective(ObjectiveType.DEFEAT, World.BANDIT_LEADER, 1),
                        new Objective(ObjectiveType.FETCH, World.RELIC, 1)), new Reward(50, 50, null));
        state.getQuestLog().accept(main);
    }

    private void run(GameCommand command) {
        assertTrue(rules.executeAll(List.of(command), state).get(0).isSuccess());
    }

    @Test
    void theGameIsNotOverBeforeItStartsOrWhileTheMainQuestIsActive() {
        assertFalse(new GameState().isGameOver());
        assertFalse(state.isGameOver());
    }

    @Test
    void theRelicLiesFreeInTheBanditCampWithTheLeader() {
        var camp = state.getWorld().findLocation("bandit-camp");

        assertNull(camp.findItem(World.RELIC).getOwnerId(), "taking it back is not a theft");
        assertEquals(World.BANDIT_LEADER, camp.getEnemies().get(0).getName());
    }

    @Test
    void recoveringTheRelicAndDefeatingTheLeaderCompletesTheMainQuestAndEndsTheGame() {
        run(new EquipCommand("sword"));
        run(new MoveCommand(Direction.SOUTH));
        run(new MoveCommand(Direction.SOUTH));
        run(new TakeCommand(World.RELIC));
        assertFalse(state.isGameOver(), "the leader still holds the camp");
        run(new AttackCommand(World.BANDIT_LEADER));
        assertFalse(state.isGameOver());

        run(new AttackCommand(World.BANDIT_LEADER));

        assertEquals(QuestStatus.COMPLETED, main.getStatus());
        assertTrue(state.isGameOver());
        GameEvent completed = state.getEventLog().filter(EventType.QUEST).get(0);
        assertEquals(0, completed.getAlignmentDelta(), "the main quest scores no alignment");
        assertEquals(0, state.getEventLog().recent(100).stream().mapToInt(GameEvent::getAlignmentDelta).sum());
    }

    @Test
    void completingASideQuestDoesNotEndTheGame() {
        state.getQuestLog().accept(new Quest("tavern-keeper:scout", "Scout the Forest Edge", "Look around.",
                "tavern-keeper", false, List.of(new Objective(ObjectiveType.VISIT, "forest-edge", 1)),
                new Reward(5, 5, null)));

        run(new MoveCommand(Direction.SOUTH));

        assertEquals(QuestStatus.COMPLETED, state.getQuestLog().allQuests().get(1).getStatus());
        assertFalse(state.isGameOver());
    }

    @Test
    void theGameIsOverWhenThePlayersHpReachesZero() {
        Player ari = state.getPlayer();
        ari.takeDamage(ari.getHp() - 1);
        assertFalse(state.isGameOver());

        ari.takeDamage(1);

        assertTrue(state.isGameOver());
    }

    @Test
    void startingAgainEmptiesTheLogsAndTheMemory() {
        run(new MoveCommand(Direction.SOUTH));
        state.getStoryMemory().addFixedFact("Ari went south.");

        state.start(CharacterFactory.forClass(CharacterClass.MAGE).createCharacter("Bo", new Stats(2, 3, 5)),
                World.createDefault());

        assertTrue(state.getQuestLog().allQuests().isEmpty());
        assertTrue(state.getEventLog().recent(10).isEmpty());
        assertTrue(state.getStoryMemory().getFixedFacts().isEmpty());
        assertEquals(1, state.getTurn());
        assertFalse(state.isGameOver());
    }
}
