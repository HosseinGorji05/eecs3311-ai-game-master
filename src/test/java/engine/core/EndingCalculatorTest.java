package engine.core;

import engine.character.CharacterClass;
import engine.character.CharacterFactory;
import engine.character.Player;
import engine.character.Stats;
import engine.events.EventLog;
import engine.events.EventType;
import engine.events.EventVerb;
import engine.events.GameEvent;
import engine.world.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The deterministic part of the ending (F12, SD09 n2 and n3), for Ari the warrior. */
class EndingCalculatorTest {

    private final EndingCalculator calculator = new EndingCalculator();
    private GameState state;

    @BeforeEach
    void startGame() {
        state = new GameState();
        state.start(CharacterFactory.forClass(CharacterClass.WARRIOR).createCharacter("Ari", new Stats(5, 3, 2)),
                World.createDefault());
    }

    private static EventLog log(GameEvent... events) {
        EventLog log = new EventLog();
        log.restore(List.of(events));
        return log;
    }

    private static GameEvent delta(int turn, int delta) {
        return new GameEvent(EventType.QUEST, turn, "an outcome", "quest", delta);
    }

    @Test
    void theScoreIsTheSumOfEveryAlignmentDelta() {
        EventLog log = log(
                new GameEvent(EventType.QUEST, 3, "Quest completed: Herbs", "herbs", 2, EventVerb.COMPLETED),
                new GameEvent(EventType.ITEM, 4, "stole the iron key from Brann", "iron key", -2, EventVerb.TOOK),
                new GameEvent(EventType.QUEST, 5, "Quest failed: Potion", "potion", -1, EventVerb.FAILED),
                new GameEvent(EventType.QUEST, 6, "Quest completed: Axe", "axe", 2, EventVerb.COMPLETED),
                new GameEvent(EventType.MOVEMENT, 7, "moved to Tavern", "tavern", 0, EventVerb.MOVED),
                new GameEvent(EventType.QUEST, 8, "Quest completed: Recover the village relic", "main-quest", 0,
                        EventVerb.COMPLETED));

        assertEquals(2 - 2 - 1 + 2, calculator.alignmentScore(log));
        assertEquals(0, calculator.alignmentScore(new EventLog()));
    }

    @ParameterizedTest
    @CsvSource({"3, HEROIC", "4, HEROIC", "2, NEUTRAL", "0, NEUTRAL", "-2, NEUTRAL", "-3, DARK", "-7, DARK"})
    void aSurvivorsEndingFollowsTheThresholdsOfThree(int score, EndingType expected) {
        assertEquals(expected, calculator.determineType(state, score));
    }

    @Test
    void deathWinsOverAnyScore() {
        Player ari = state.getPlayer();
        ari.takeDamage(ari.getHp());

        assertEquals(EndingType.DEATH, calculator.determineType(state, 10));
        assertEquals(EndingType.DEATH, calculator.determineType(state, 0));
        assertEquals(EndingType.DEATH, calculator.determineType(state, -10));
    }

    @Test
    void theStatisticsCountTurnsCompletedQuestsAndDefeatedEnemies() {
        EventLog log = log(
                new GameEvent(EventType.COMBAT, 2, "wolf defeated", "wolf", 0, EventVerb.DEFEATED),
                new GameEvent(EventType.QUEST, 2, "Quest completed: Herbs", "herbs", 2, EventVerb.COMPLETED),
                new GameEvent(EventType.QUEST, 4, "Quest failed: Potion", "potion", -1, EventVerb.FAILED),
                new GameEvent(EventType.QUEST, 5, "Quest accepted: Axe", "axe", 0, EventVerb.ACCEPTED),
                new GameEvent(EventType.COMBAT, 6, "Ari attacks bandit leader", "bandit leader", 0),
                new GameEvent(EventType.REJECTED, 7, "Rejected MOVE: no exit", "MOVE", 0),
                new GameEvent(EventType.COMBAT, 9, "bandit leader defeated", "bandit leader", 0, EventVerb.DEFEATED),
                new GameEvent(EventType.QUEST, 9, "Quest completed: Recover the village relic", "main-quest", 0,
                        EventVerb.COMPLETED));

        Map<String, Integer> statistics = calculator.statistics(log);

        assertEquals(List.of(EndingCalculator.TURNS_PLAYED, EndingCalculator.QUESTS_COMPLETED,
                EndingCalculator.ENEMIES_DEFEATED), List.copyOf(statistics.keySet()), "in this order");
        assertEquals(9, statistics.get(EndingCalculator.TURNS_PLAYED));
        assertEquals(2, statistics.get(EndingCalculator.QUESTS_COMPLETED), "the main quest counts");
        assertEquals(2, statistics.get(EndingCalculator.ENEMIES_DEFEATED));
    }

    @Test
    void anEmptyLogHasZeroStatistics() {
        assertEquals(Map.of(EndingCalculator.TURNS_PLAYED, 0, EndingCalculator.QUESTS_COMPLETED, 0,
                EndingCalculator.ENEMIES_DEFEATED, 0), calculator.statistics(new EventLog()));
    }

    @Test
    void anEndingKeepsItsTypeStatisticsAndEpilogue() {
        Ending ending = new Ending(EndingType.NEUTRAL, calculator.statistics(log(delta(4, 0))));

        assertEquals(EndingType.NEUTRAL, ending.getType());
        assertEquals(4, ending.getStatistic(EndingCalculator.TURNS_PLAYED));
        assertEquals(0, ending.getStatistic("gold found"));
        assertEquals(null, ending.getEpilogue());
        ending.setEpilogue("The relic came home.");
        assertEquals("The relic came home.", ending.getEpilogue());
        assertThrows(UnsupportedOperationException.class, () -> ending.getStatistics().put("x", 1));
        assertThrows(NullPointerException.class, () -> ending.setEpilogue(null));
    }
}
