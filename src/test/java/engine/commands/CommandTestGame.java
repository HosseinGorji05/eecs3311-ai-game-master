package engine.commands;

import engine.character.CharacterClass;
import engine.character.CharacterFactory;
import engine.character.Enemy;
import engine.character.Player;
import engine.character.Stats;
import engine.core.CombatMode;
import engine.core.FixedDiceRoller;
import engine.core.GameState;
import engine.core.RulesEngine;
import engine.events.EventType;
import engine.events.GameEvent;
import engine.events.GameEventBus;
import engine.quests.Objective;
import engine.quests.ObjectiveType;
import engine.quests.Quest;
import engine.quests.QuestTracker;
import engine.quests.Reward;
import engine.world.World;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Test helper for the item and read-only commands: a default world with "Ari" (strength 4, agility
 * 3, so attack bonus 4 and defense 13) in a chosen location, a bus with the event log and a quest
 * tracker, and every published event recorded. Dice are planned per call; a test that rolls more
 * than planned fails.
 */
final class CommandTestGame {

    final GameState state = new GameState();
    final Player ari;
    final List<GameEvent> published = new ArrayList<>();
    private final GameEventBus bus = new GameEventBus();
    private FixedDiceRoller dice;

    CommandTestGame(CharacterClass cls, String locationId) {
        ari = CharacterFactory.forClass(cls).createCharacter("Ari", new Stats(4, 3, 3));
        state.start(ari, World.createDefault());
        state.setCurrentLocation(state.getWorld().findLocation(locationId));
        bus.subscribe(published::add);   // first, so events are recorded in the order they are published
        bus.subscribe(state.getEventLog());
        bus.subscribe(new QuestTracker(state, bus));
    }

    /** Runs one command as a turn with exactly these d20 rolls. */
    ActionResult run(GameCommand command, int... rolls) {
        dice = new FixedDiceRoller(rolls);
        List<ActionResult> results = new RulesEngine(dice, bus).executeAll(List.of(command), state);
        assertEquals(0, dice.remaining(), "every planned roll is used");
        return results.get(0);
    }

    /** Runs a command that must be rejected without rolling, changing nothing but the turn number. */
    void assertRejectedWithoutChange(GameCommand command, String expectedMessage) {
        String before = StateFingerprint.of(state);
        int turn = state.getTurn();
        published.clear();

        ActionResult result = run(command);

        assertFalse(result.isSuccess());
        assertEquals(expectedMessage, result.getMessage());
        assertEquals(before, StateFingerprint.of(state));
        assertEquals(turn + 1, state.getTurn());
        assertEquals(List.of(EventType.REJECTED), published.stream().map(GameEvent::getType).toList());
    }

    /** Starts a fight with the enemy in the current location, as a first attack would. */
    Enemy fight(String enemyName) {
        Enemy enemy = state.currentLocation().findEnemy(enemyName);
        state.setMode(new CombatMode(enemy));
        return enemy;
    }

    /** Accepts an active side quest with one FETCH objective for the item. */
    Quest fetchQuest(String itemName) {
        Quest quest = new Quest("fetch-" + itemName, "Bring the " + itemName, "A side quest.", "blacksmith", false,
                List.of(new Objective(ObjectiveType.FETCH, itemName, 1)), new Reward(5, 10, null));
        state.getQuestLog().accept(quest);
        return quest;
    }

    List<EventType> publishedTypes() {
        return published.stream().map(GameEvent::getType).toList();
    }
}
