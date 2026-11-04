package engine.core;

import engine.character.CharacterClass;
import engine.character.CharacterFactory;
import engine.character.Enemy;
import engine.character.Player;
import engine.character.Stats;
import engine.events.EventLog;
import engine.events.EventType;
import engine.events.EventVerb;
import engine.events.GameEvent;
import engine.persistence.GameSnapshot;
import engine.quests.QuestLog;
import engine.quests.Reward;
import engine.world.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GameState as the Originator (F10, SD08): Ari the mage's state is saved into a snapshot and restored. */
class GameStateSnapshotTest {

    private GameState state;

    @BeforeEach
    void startGame() {
        state = new GameState();
        state.start(CharacterFactory.forClass(CharacterClass.MAGE).createCharacter("Ari", new Stats(2, 3, 5)),
                World.createDefault());
    }

    private static GameSnapshot withState(GameSnapshot snapshot, String from, String to) throws IOException {
        String json = snapshot.toJson();
        assertTrue(json.contains(from), from);
        return GameSnapshot.fromJson(json.replace(from, to));
    }

    @Test
    void thereIsNoSnapshotBeforeTheGameStartsOrDuringCombat() {
        assertThrows(IllegalStateException.class, () -> new GameState().createSnapshot());
        state.setMode(new CombatMode(new Enemy("wolf", 8, 3, 11)));

        assertThrows(IllegalStateException.class, state::createSnapshot);
    }

    @Test
    void theSnapshotIsNamedAfterThePlayerAndTheLocation() {
        GameSnapshot snapshot = state.createSnapshot();

        assertEquals("Ari", snapshot.getCharacterName());
        assertEquals("Village Square", snapshot.getLocationName());
        assertTrue(snapshot.isCompatible());
    }

    @Test
    void woundsRewardsTheBackstoryAWoundedEnemyAndVisitedPlacesComeBack() {
        Player ari = state.getPlayer();
        ari.takeDamage(5);
        ari.addReward(new Reward(12, 140, null));
        ari.setBackstory("A hedge mage from the hills.");
        state.getWorld().findLocation("wolf-den").getEnemies().get(0).takeDamage(3);   // fled from the wolf
        state.getWorld().findLocation("tavern").markVisited();
        state.setCurrentLocation(state.getWorld().findLocation("tavern"));
        state.nextTurn();
        state.getEventLog().onEvent(new GameEvent(EventType.MOVEMENT, 1, "moved to Tavern", "tavern", 0,
                EventVerb.MOVED, Instant.parse("2026-11-03T10:15:30.123456789Z")));
        GameSnapshot snapshot = state.createSnapshot();

        GameState loaded = new GameState();
        loaded.restore(snapshot);

        assertEquals(snapshot.getStateJson(), loaded.createSnapshot().getStateJson());
        Player restored = loaded.getPlayer();
        assertEquals(13, restored.getHp());
        assertEquals(18, restored.getMaxHp());
        assertEquals(12, restored.getGold());
        assertEquals(2, restored.getLevel());
        assertEquals("A hedge mage from the hills.", restored.getBackstory());
        assertEquals(CharacterClass.MAGE, restored.getCharacterClass());
        assertEquals(5, loaded.getWorld().findLocation("wolf-den").getEnemies().get(0).getHp());
        assertTrue(loaded.getWorld().findLocation("tavern").isVisited());
        assertEquals("tavern", loaded.currentLocation().getId());
        assertEquals(2, loaded.getTurn());
        assertEquals(Instant.parse("2026-11-03T10:15:30.123456789Z"), loaded.getEventLog().recent(1).get(0).getTimestamp());
        assertTrue(loaded.getMode() instanceof ExplorationMode);
    }

    @Test
    void restoreRefillsTheSameLogsAndMemory() {
        EventLog eventLog = state.getEventLog();
        QuestLog questLog = state.getQuestLog();
        StoryMemory memory = state.getStoryMemory();
        GameSnapshot snapshot = state.createSnapshot();
        eventLog.onEvent(new GameEvent(EventType.MOVEMENT, 1, "moved to Tavern", "tavern", 0, EventVerb.MOVED));
        memory.addFixedFact("something that will be forgotten");

        state.restore(snapshot);

        assertSame(eventLog, state.getEventLog());
        assertSame(questLog, state.getQuestLog());
        assertSame(memory, state.getStoryMemory());
        assertTrue(eventLog.recent(10).isEmpty());
        assertTrue(memory.getFixedFacts().isEmpty());
    }

    @Test
    void anIncompatibleOrDamagedSnapshotChangesNothing() throws IOException {
        GameSnapshot good = state.createSnapshot();
        state.getPlayer().takeDamage(4);
        state.nextTurn();
        String before = state.createSnapshot().getStateJson();

        assertThrows(IllegalArgumentException.class, () -> state.restore(withState(good, "\"version\": 2", "\"version\": 1")));
        assertThrows(IllegalArgumentException.class, () -> state.restore(withState(good, "\"hp\": 18", "\"hp\": 0")));
        assertThrows(IllegalArgumentException.class, () -> state.restore(withState(good, "\"kind\": \"weapon\"",
                "\"kind\": \"spell\"")));
        assertThrows(IllegalArgumentException.class, () -> state.restore(withState(good, "\"locked\": false",
                "\"locked\": true")), "a locked exit needs a key");
        assertThrows(IllegalArgumentException.class, () -> state.restore(withState(good, "\"turn\": 1,", "\"turn\": 1.5,")));
        assertThrows(IllegalArgumentException.class, () -> state.restore(withState(good, "\"memory\": {",
                "\"memory\": 3, \"x\": {")));

        assertEquals(before, state.createSnapshot().getStateJson());
    }
}
