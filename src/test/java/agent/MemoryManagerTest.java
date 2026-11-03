package agent;

import engine.character.CharacterClass;
import engine.character.CharacterFactory;
import engine.character.Stats;
import engine.core.GameState;
import engine.core.StoryMemory;
import engine.events.EventType;
import engine.events.EventVerb;
import engine.events.GameEvent;
import engine.events.GameEventBus;
import engine.world.World;
import llm.LLMClient;
import llm.LlmRequest;
import llm.LlmResponse;
import llm.ScriptedLLMClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ari the warrior's story memory, filled through the event bus as in the game (SD05, SD06). */
class MemoryManagerTest {

    private GameState state;
    private GameEventBus bus;
    private MemoryManager manager;
    private CountingClient client;

    @BeforeEach
    void startGame() {
        state = new GameState();
        state.start(CharacterFactory.forClass(CharacterClass.WARRIOR).createCharacter("Ari", new Stats(5, 3, 2)),
                World.createDefault());
        client = new CountingClient();
        useClient(client);
    }

    /** Subscribes the event log and a memory manager whose summarizer asks {@code llm}. */
    private void useClient(LLMClient llm) {
        bus = new GameEventBus();
        bus.subscribe(state.getEventLog());
        manager = new MemoryManager(state, new MemorySummarizer(llm, new PromptBuilder(), new ResponseParser(), state));
        bus.subscribe(manager);
    }

    private StoryMemory memory() {
        return state.getStoryMemory();
    }

    /** An available client that counts requests and answers each with a valid summary. */
    private static final class CountingClient implements LLMClient {
        int calls;

        @Override
        public LlmResponse complete(LlmRequest request) {
            calls++;
            return new LlmResponse("{\"summary\":\"Ari wandered between the Tavern and the Village Square.\"}", true, null);
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String providerName() {
            return "counting";
        }
    }

    private static GameEvent moved(int turn) {
        return new GameEvent(EventType.MOVEMENT, turn, "moved to place " + turn, "place-" + turn, 0, EventVerb.MOVED);
    }

    private void publishMoves(int fromTurn, int toTurn) {
        for (int turn = fromTurn; turn <= toTurn; turn++) {
            bus.publish(moved(turn));
        }
    }

    @Test
    void everyEventExceptARejectedOneIsRecorded() {
        bus.publish(moved(1));
        bus.publish(new GameEvent(EventType.ITEM, 2, "took the bread", "bread", 0, EventVerb.TOOK));
        bus.publish(new GameEvent(EventType.REJECTED, 2, "Rejected MOVE: You can't go that way.", "MOVE", 0));
        bus.publish(new GameEvent(EventType.COMBAT, 3, "Ari attacks wolf: hit", "wolf", 0));
        bus.publish(new GameEvent(EventType.DIALOGUE, 4, "started talking to Brann", "blacksmith", 0));
        bus.publish(new GameEvent(EventType.QUEST, 4, "Quest accepted: The Wolf", "blacksmith:the-wolf", 0,
                EventVerb.ACCEPTED));

        assertEquals(List.of(EventType.MOVEMENT, EventType.ITEM, EventType.COMBAT, EventType.DIALOGUE, EventType.QUEST),
                memory().getRecentEvents().stream().map(GameEvent::getType).toList());
        assertEquals(6, state.getEventLog().recent(100).size(), "the event history still has the rejected attempt");
    }

    @Test
    void deathsQuestOutcomesAndTheftsAreKeptAsFixedFacts() {
        bus.publish(new GameEvent(EventType.COMBAT, 1, "wolf defeated", "wolf", 0, EventVerb.DEFEATED));
        bus.publish(new GameEvent(EventType.ITEM, 2, "stole the iron key from Brann", "iron key", -2, EventVerb.TOOK));
        bus.publish(new GameEvent(EventType.QUEST, 3, "Quest completed: The Wolf. Reward: 25 gold, 25 XP.",
                "blacksmith:the-wolf", 2, EventVerb.COMPLETED));
        bus.publish(new GameEvent(EventType.QUEST, 4, "Quest failed: Herbs", "blacksmith:herbs", -1, EventVerb.FAILED));
        bus.publish(new GameEvent(EventType.COMBAT, 5, "Ari died", "Ari", 0, EventVerb.DIED));

        assertEquals(List.of("wolf defeated", "stole the iron key from Brann",
                "Quest completed: The Wolf. Reward: 25 gold, 25 XP.", "Quest failed: Herbs", "Ari died"),
                memory().getFixedFacts());
    }

    @Test
    void ordinaryEventsAreNoFixedFacts() {
        bus.publish(moved(1));
        bus.publish(new GameEvent(EventType.ITEM, 2, "took the bread", "bread", 0, EventVerb.TOOK));
        bus.publish(new GameEvent(EventType.COMBAT, 3, "Ari attacks wolf: hit", "wolf", 0));
        bus.publish(new GameEvent(EventType.COMBAT, 3, "fled from the wolf", "wolf", 0, EventVerb.FLED));
        bus.publish(new GameEvent(EventType.QUEST, 4, "Quest accepted: The Wolf", "q", 0, EventVerb.ACCEPTED));

        assertTrue(memory().getFixedFacts().isEmpty());
    }

    @Test
    void nothingIsSummarizedUpToTheThreshold() {
        publishMoves(1, MemoryManager.SUMMARIZE_THRESHOLD);

        assertEquals(MemoryManager.SUMMARIZE_THRESHOLD, memory().recentCount());
        assertTrue(memory().getSummaries().isEmpty());
        assertEquals(0, client.calls, "no model call below or at the threshold");
    }

    @Test
    void oneMoreEventThanTheThresholdSummarizesTheOldestBatch() {
        publishMoves(1, MemoryManager.SUMMARIZE_THRESHOLD + 1);

        assertEquals(1, client.calls);
        assertEquals(List.of("Turns 1-10: Ari wandered between the Tavern and the Village Square."), memory().getSummaries());
        assertEquals(MemoryManager.SUMMARIZE_THRESHOLD + 1 - MemoryManager.SUMMARY_BATCH, memory().recentCount());
        assertEquals(11, memory().getRecentEvents().get(0).getTurn());

        publishMoves(22, 31);
        assertEquals(2, memory().getSummaries().size(), "the next batch is summarized ten events later");
        assertTrue(memory().getSummaries().get(1).startsWith("Turns 11-20: "));
    }

    @Test
    void offlineTheSummaryIsTheEventsOwnDescriptions() {
        useClient(new ScriptedLLMClient(List.of()));

        publishMoves(1, 21);

        assertEquals("Turns 1-10: moved to place 1; moved to place 2; moved to place 3; moved to place 4; "
                + "moved to place 5; moved to place 6; moved to place 7; moved to place 8; moved to place 9; "
                + "moved to place 10.", memory().getSummaries().get(0));
    }

    @Test
    void aSummaryCoveringOneTurnNamesThatTurn() {
        useClient(new ScriptedLLMClient(List.of()));

        for (int i = 0; i < 21; i++) {
            bus.publish(new GameEvent(EventType.COMBAT, 7, "Ari attacks wolf: miss " + i, "wolf", 0));
        }

        assertTrue(memory().getSummaries().get(0).startsWith("Turn 7: Ari attacks wolf: miss 0;"));
    }

    @Test
    void aClientThatThrowsNeverStopsTheEventFromBeingHandled() {
        MemorySummarizerTest.ThrowingClient throwing = new MemorySummarizerTest.ThrowingClient();
        useClient(throwing);

        assertDoesNotThrow(() -> publishMoves(1, 21));

        assertEquals(2, throwing.calls, "asked twice, then the fallback");
        assertTrue(memory().getSummaries().get(0).startsWith("Turns 1-10: moved to place 1;"));
        assertEquals(21, state.getEventLog().recent(100).size());
    }

    @Test
    void aBrokenSummarizerStillLeadsToTheFallbackSummary() {
        bus = new GameEventBus();
        manager = new MemoryManager(state, new MemorySummarizer(new CountingClient(), new PromptBuilder(Map.of()),
                new ResponseParser(), state));   // no templates: building the prompt throws
        bus.subscribe(manager);

        assertDoesNotThrow(() -> publishMoves(1, 21));

        assertTrue(memory().getSummaries().get(0).startsWith("Turns 1-10: moved to place 1;"));
    }

    @Test
    void fixedFactsOutliveTheSummaries() {
        bus.publish(new GameEvent(EventType.ITEM, 1, "stole the iron key from Brann", "iron key", -2, EventVerb.TOOK));

        publishMoves(2, 200);

        assertEquals(List.of("stole the iron key from Brann"), memory().getFixedFacts());
        assertTrue(memory().getRecentEvents().stream().noneMatch(event -> event.getTurn() == 1));
    }

    @Test
    void memoryStaysWithinItsCapsOverAThousandEvents() {
        manager.addStartingFacts();
        int facts = memory().getFixedFacts().size();

        for (int turn = 1; turn <= 1000; turn++) {
            bus.publish(new GameEvent(EventType.DIALOGUE, turn, "Ari said \"" + "tell me more ".repeat(30)
                    + "\" to Brann", "blacksmith", 0));
            assertTrue(memory().recentCount() <= MemoryManager.SUMMARIZE_THRESHOLD);
        }

        assertEquals(StoryMemory.MAX_SUMMARIES, memory().getSummaries().size());
        assertEquals(facts, memory().getFixedFacts().size());
        memory().getSummaries().forEach(summary -> assertTrue(summary.length() <= StoryMemory.MAX_ENTRY_CHARS));
        memory().getRecentEvents().forEach(event ->
                assertTrue(event.getDescription().length() <= StoryMemory.MAX_EVENT_CHARS));
        assertTrue(manager.buildContext("blacksmith", PromptBuilder.MAX_MEMORY_CHARS).length()
                <= PromptBuilder.MAX_MEMORY_CHARS);
        int bound = (StoryMemory.MAX_SUMMARIES + facts) * (StoryMemory.MAX_ENTRY_CHARS + 3)
                + MemoryManager.SUMMARIZE_THRESHOLD * (StoryMemory.MAX_EVENT_CHARS + 20) + 100;
        assertTrue(manager.journalText().length() < bound, "the journal is bounded too");
    }

    @Test
    void theStartingFactsNameThePlayerTheirKitAndThePeople() {
        assertEquals(List.of(
                "Ari, a warrior, arrived at the Village Square in the Village when the story began.",
                "Ari set out carrying: sword, healing potion.",
                "Marta the tavern keeper can be found at the Tavern.",
                "Brann the blacksmith can be found at the Blacksmith's Forge."),
                MemoryManager.startingFacts(state));

        manager.addStartingFacts();
        assertEquals(MemoryManager.startingFacts(state), memory().getFixedFacts());
    }

    @Test
    void aMageSetsOutWithTwoPotions() {
        state = new GameState();
        state.start(CharacterFactory.forClass(CharacterClass.MAGE).createCharacter("Vex", new Stats(2, 3, 5)),
                World.createDefault());

        assertEquals("Vex set out carrying: staff, healing potion (2).", MemoryManager.startingFacts(state).get(1));
    }

    @Test
    void theContextIsEmptyWhenNothingIsRemembered() {
        assertEquals(MemoryManager.NOTHING_REMEMBERED, manager.buildContext("forge", 500));
        assertEquals("", manager.buildContext("forge", 5), "not even that fits");
    }

    @Test
    void theContextShowsFactsThenEarlierNotesThenRecentEvents() {
        useClient(new ScriptedLLMClient(List.of()));
        memory().addFixedFact("wolf defeated");
        memory().addSummary("Turns 1-10: Ari explored the village.");
        publishMoves(11, 12);

        assertEquals("""
                Facts:
                - wolf defeated
                Earlier:
                - Turns 1-10: Ari explored the village.
                Recent events:
                - [turn 11] moved to place 11
                - [turn 12] moved to place 12""", manager.buildContext(null, 1000));
    }

    @Test
    void whenSpaceIsShortWhatConcernsTheFocusIsKeptAndTheRestIsDropped() {
        memory().addFixedFact("wolf defeated");
        memory().addSummary("Turns 1-10: Ari explored the village and met Brann at the forge.");
        memory().addSummary("Turns 11-20: Ari wandered in the forest.");
        bus.publish(new GameEvent(EventType.DIALOGUE, 21, "started talking to Brann", "blacksmith", 0));
        publishMoves(22, 30);

        String context = manager.buildContext("blacksmith", 160);

        assertTrue(context.length() <= 160, context);
        assertTrue(context.startsWith("Facts:\n- wolf defeated\n"), context);
        assertTrue(context.contains("met Brann at the forge"), "a summary naming the NPC goes first: " + context);
        assertTrue(context.contains("started talking to Brann"), context);
        assertFalse(context.contains("wandered in the forest"), context);
        assertFalse(context.contains("place 22"), "the oldest other events go first: " + context);
    }

    @Test
    void aLocationFocusAlsoMatchesTheLocationsName() {
        memory().addSummary("Turns 1-10: Ari sold bread at the Tavern.");
        memory().addSummary("Turns 11-20: " + "Ari wandered about. ".repeat(4));

        String context = manager.buildContext("tavern", 70);

        assertTrue(context.contains("sold bread at the Tavern"), context);
        assertFalse(context.contains("wandered"), context);
    }

    @Test
    void theContextNeverExceedsItsLimit() {
        manager.addStartingFacts();
        memory().addFixedFact("stole the iron key from Brann");
        for (int i = 1; i <= 8; i++) {
            memory().addSummary("Turns " + i + ": " + "a long summary of what happened ".repeat(10));
        }
        publishMoves(1, 15);

        for (int limit = 0; limit <= 1500; limit += 37) {
            String context = manager.buildContext("blacksmith", limit);
            assertTrue(context.length() <= limit, limit + ": " + context.length());
        }
    }

    @Test
    void theJournalSaysTheStoryHasJustBegunUntilSomethingHappens() {
        assertEquals("Journal of Ari\nThe story has just begun.", manager.journalText());

        manager.addStartingFacts();
        assertEquals("""
                Journal of Ari
                Facts:
                - Ari, a warrior, arrived at the Village Square in the Village when the story began.
                - Ari set out carrying: sword, healing potion.
                - Marta the tavern keeper can be found at the Tavern.
                - Brann the blacksmith can be found at the Blacksmith's Forge.
                The story has just begun.""", manager.journalText());
    }

    @Test
    void theJournalShowsAllOfTheMemoryWithoutCallingTheModel() {
        bus.publish(new GameEvent(EventType.COMBAT, 1, "wolf defeated", "wolf", 0, EventVerb.DEFEATED));
        publishMoves(2, 22);
        int calls = client.calls;

        String journal = manager.journalText();

        assertEquals(calls, client.calls);
        assertTrue(journal.startsWith("""
                Journal of Ari
                Facts:
                - wolf defeated
                Earlier:
                - Turns 1-10: Ari wandered between the Tavern and the Village Square.
                Recent events:
                - [turn 11] moved to place 11
                """), journal);
        assertTrue(journal.endsWith("- [turn 22] moved to place 22"), journal);
    }
}
