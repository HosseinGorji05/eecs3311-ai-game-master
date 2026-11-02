package engine.quests;

import engine.character.CharacterClass;
import engine.character.CharacterFactory;
import engine.character.Player;
import engine.character.Stats;
import engine.commands.MoveCommand;
import engine.core.DiceRoller;
import engine.core.GameState;
import engine.core.RulesEngine;
import engine.events.EventType;
import engine.events.GameEvent;
import engine.events.GameEventBus;
import engine.items.Item;
import engine.world.Direction;
import engine.world.Location;
import engine.world.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reward item of a completed quest (F08): Ari the warrior (carrying 3.5 kg) completes Brann's
 * quest by walking from the Village Square to the Forest Edge.
 */
class QuestRewardItemTest {

    private GameState state;
    private RulesEngine rules;
    private Player ari;
    private Location forge;

    @BeforeEach
    void startGame() {
        state = new GameState();
        ari = CharacterFactory.forClass(CharacterClass.WARRIOR).createCharacter("Ari", new Stats(5, 3, 2));
        state.start(ari, World.createDefault());
        GameEventBus bus = new GameEventBus();
        bus.subscribe(state.getEventLog());
        bus.subscribe(new QuestTracker(state, bus));
        rules = new RulesEngine(new DiceRoller(0), bus);
        forge = state.getWorld().findLocation("forge");
    }

    /** Accepts Brann's scouting quest with this reward item and walks south to complete it. */
    private Quest completeScoutingQuest(String rewardItem) {
        Quest quest = new Quest("blacksmith:scout", "Scout the Forest Edge", "Look around.", "blacksmith", false,
                List.of(new Objective(ObjectiveType.VISIT, "forest-edge", 1)), new Reward(20, 15, rewardItem));
        state.getQuestLog().accept(quest);
        assertTrue(rules.executeAll(List.of(new MoveCommand(Direction.SOUTH)), state).get(0).isSuccess());
        assertEquals(QuestStatus.COMPLETED, quest.getStatus());
        return quest;
    }

    private String completionMessage() {
        List<GameEvent> quests = state.getEventLog().filter(EventType.QUEST);
        assertEquals(1, quests.size(), "completed exactly once");
        return quests.get(0).getDescription();
    }

    @Test
    void anItemThatFitsLeavesTheGiverAndGoesIntoTheInventoryOnce() {
        Item key = forge.findItem("iron key");

        completeScoutingQuest("iron key");

        assertSame(key, ari.getInventory().find("iron key"));
        assertNull(key.getOwnerId(), "it is Ari's now");
        assertNull(forge.findItem("iron key"));
        assertEquals(20, ari.getGold());
        assertEquals(15, ari.getXp());
        assertEquals("Quest completed: Scout the Forest Edge. Reward: 20 gold, 15 XP, the iron key. "
                + "Brann gives you the iron key.", completionMessage());
        GameEvent received = state.getEventLog().filter(EventType.ITEM).get(0);
        assertEquals("received the iron key from Brann as a quest reward", received.getDescription());
        assertNull(received.getVerb(), "never counts as a FETCH");
        assertEquals(0, received.getAlignmentDelta());

        rules.executeAll(List.of(new MoveCommand(Direction.NORTH)), state);
        rules.executeAll(List.of(new MoveCommand(Direction.SOUTH)), state);
        assertEquals(20, ari.getGold(), "the reward is never granted twice");
        assertEquals(1, state.getEventLog().filter(EventType.QUEST).size());
    }

    @Test
    void anItemTooHeavyToCarryStaysWithTheGiverButBecomesThePlayers() {
        Item warhammer = forge.findItem("warhammer");

        completeScoutingQuest("warhammer");

        assertSame(warhammer, forge.findItem("warhammer"), "3.5 + 8 kg is over the 10 kg limit");
        assertNull(warhammer.getOwnerId(), "taking it later is no theft");
        assertNull(ari.getInventory().find("warhammer"));
        assertEquals(20, ari.getGold(), "gold and XP are still granted");
        assertTrue(completionMessage().endsWith("The warhammer is too heavy for you to carry, so Brann leaves it "
                + "for you at the Blacksmith's Forge."), completionMessage());
    }

    @Test
    void anItemThePlayerAlreadyTookIsTheirsToKeep() {
        Item key = forge.removeItem("iron key");
        ari.getInventory().add(key);   // stolen earlier, still Brann's

        completeScoutingQuest("iron key");

        assertNull(key.getOwnerId());
        assertTrue(completionMessage().endsWith("Brann lets you keep the iron key you took."), completionMessage());
    }

    @Test
    void anItemThatIsGoneIsNotGranted() {
        forge.removeItem("iron key");

        completeScoutingQuest("iron key");

        assertNull(ari.getInventory().find("iron key"));
        assertEquals(15, ari.getXp());
        assertTrue(completionMessage().endsWith("Brann no longer has the iron key."), completionMessage());
    }

    @Test
    void aQuestWithoutAnItemOnlyNamesGoldAndXp() {
        completeScoutingQuest(null);

        assertEquals("Quest completed: Scout the Forest Edge. Reward: 20 gold, 15 XP.", completionMessage());
        assertTrue(state.getEventLog().filter(EventType.ITEM).isEmpty());
    }
}
