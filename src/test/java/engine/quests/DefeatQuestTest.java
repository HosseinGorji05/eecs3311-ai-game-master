package engine.quests;

import engine.character.CharacterClass;
import engine.character.CharacterFactory;
import engine.character.Player;
import engine.character.Stats;
import engine.commands.AttackCommand;
import engine.commands.GameCommand;
import engine.core.FixedDiceRoller;
import engine.core.GameState;
import engine.core.RulesEngine;
import engine.events.EventType;
import engine.events.EventVerb;
import engine.events.GameEvent;
import engine.events.GameEventBus;
import engine.items.Weapon;
import engine.world.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A DEFEAT quest completed by real attacks through the rules engine, the bus and the tracker. */
class DefeatQuestTest {

    private GameState state;
    private GameEventBus bus;
    private Player ari;

    @BeforeEach
    void startGame() {
        state = new GameState();
        ari = CharacterFactory.forClass(CharacterClass.WARRIOR).createCharacter("Ari", new Stats(4, 3, 3));
        ari.equip(new Weapon("sword", 3.0, 6));
        state.start(ari, World.createDefault());
        state.setCurrentLocation(state.getWorld().findLocation("wolf-den"));
        bus = new GameEventBus();
        bus.subscribe(state.getEventLog());
        bus.subscribe(new QuestTracker(state, bus));
    }

    private void attack(String target, int... rolls) {
        GameCommand command = new AttackCommand(target);
        new RulesEngine(new FixedDiceRoller(rolls), bus).executeAll(List.of(command), state);
    }

    @Test
    void killingTheWolfCompletesTheQuestOnceAndGrantsTheReward() {
        Quest hunt = new Quest("wolf-hunt", "Wolf Hunt", "Kill the wolf that scares the village.", "hunter", false,
                List.of(new Objective(ObjectiveType.DEFEAT, "wolf", 1)), new Reward(20, 30, null));
        state.getQuestLog().accept(hunt);

        attack("wolf", 15, 1);   // 6 damage: wolf at 2/8, the wolf misses
        assertEquals(QuestStatus.ACTIVE, hunt.getStatus(), "wounding is not defeating");

        attack("wolf", 15);      // dead

        assertEquals(QuestStatus.COMPLETED, hunt.getStatus());
        assertEquals(20, ari.getGold());
        assertEquals(30, ari.getXp());
        List<GameEvent> lastTurn = state.getEventLog().recent(3);
        assertEquals(List.of(EventType.COMBAT, EventType.COMBAT, EventType.QUEST),
                lastTurn.stream().map(GameEvent::getType).toList());
        assertEquals(EventVerb.DEFEATED, lastTurn.get(1).getVerb());
        assertEquals("wolf-hunt", lastTurn.get(2).getSubjectId());
        assertEquals(2, lastTurn.get(2).getAlignmentDelta());
        assertEquals(1, state.getEventLog().filter(EventType.QUEST).size());
    }

    @Test
    void defeatingAnotherEnemyDoesNotCount() {
        Quest hunt = new Quest("leader", "Bandit Hunt", "Defeat the bandit leader.", "elder", false,
                List.of(new Objective(ObjectiveType.DEFEAT, "bandit leader", 1)), new Reward(50, 50, null));
        state.getQuestLog().accept(hunt);

        attack("wolf", 15, 1);
        attack("wolf", 15);

        assertEquals(QuestStatus.ACTIVE, hunt.getStatus());
        assertEquals(0, ari.getGold());
        assertTrue(state.getEventLog().filter(EventType.QUEST).isEmpty());
    }
}
