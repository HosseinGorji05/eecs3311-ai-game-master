package agent;

import engine.character.CharacterClass;
import engine.character.CharacterFactory;
import engine.character.Stats;
import engine.core.DialogueMode;
import engine.core.GameState;
import engine.quests.Objective;
import engine.quests.ObjectiveType;
import engine.quests.Quest;
import engine.quests.Reward;
import engine.world.Location;
import engine.world.NPC;
import engine.world.World;
import llm.LLMClient;
import llm.LlmRequest;
import llm.LlmResponse;
import llm.ScriptedLLMClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ari the warrior asks Brann the blacksmith for work in the Forge; every model answer is scripted. */
class QuestGeneratorTest {

    private static final String HERBS = """
            {"title":"Herbs for the Forge","description":"My burns need herbs from the mill.",
             "objectives":[{"type":"fetch","target":"Healing Herbs","count":1},{"type":"visit","target":"Old Mill"}],
             "reward":{"gold":20,"xp":15,"item":"Iron Key"}}""";
    private static final String ZORN = """
            {"title":"The Crystal","description":"Find the Crystal of Zorn.",
             "objectives":[{"type":"fetch","target":"Crystal of Zorn","count":1}],
             "reward":{"gold":10,"xp":10,"item":null}}""";

    private GameState state;
    private NPC brann;

    @BeforeEach
    void talkToBrann() {
        state = new GameState();
        state.start(CharacterFactory.forClass(CharacterClass.WARRIOR).createCharacter("Ari", new Stats(5, 3, 2)),
                World.createDefault());
        state.setCurrentLocation(state.getWorld().findLocation("forge"));
        brann = state.currentLocation().findNpc("Brann");
        state.setMode(new DialogueMode(brann));
    }

    private QuestGenerator generator(String... responses) {
        return generator(new ScriptedLLMClient(List.of(responses)));
    }

    private QuestGenerator generator(LLMClient client) {
        return new QuestGenerator(client, new PromptBuilder(), new ResponseParser(), state);
    }

    /** A client that records every request and answers with the given text. */
    private static final class RecordingClient implements LLMClient {
        final List<LlmRequest> requests = new ArrayList<>();
        private final String answer;

        RecordingClient(String answer) {
            this.answer = answer;
        }

        @Override
        public LlmResponse complete(LlmRequest request) {
            requests.add(request);
            return new LlmResponse(answer, true, null);
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String providerName() {
            return "recording";
        }
    }

    @Test
    void aValidProposalBecomesAQuestWithTheGamesNames() {
        QuestGenerator generator = generator(HERBS);

        Quest quest = generator.generate(brann);

        assertFalse(generator.isFallbackUsed());
        assertEquals("blacksmith:herbs-for-the-forge", quest.getId());
        assertEquals("Herbs for the Forge", quest.getTitle());
        assertEquals("My burns need herbs from the mill.", quest.getDescription());
        assertEquals("blacksmith", quest.getGiverId());
        assertFalse(quest.isMain());
        assertEquals(2, quest.getObjectives().size());
        Objective fetch = quest.getObjectives().get(0);
        assertEquals(ObjectiveType.FETCH, fetch.getType());
        assertEquals("healing herbs", fetch.getTargetId());
        assertEquals(1, fetch.getRequired());
        assertEquals(0, fetch.getProgress());
        assertEquals("old-mill", quest.getObjectives().get(1).getTargetId(), "count defaults to 1, name becomes id");
        assertEquals(20, quest.getReward().getGold());
        assertEquals(15, quest.getReward().getXp());
        assertEquals("iron key", quest.getReward().getItemName());
        assertTrue(state.getQuestLog().allQuests().isEmpty(), "generating only offers; nothing is accepted");
    }

    @Test
    void anInvalidProposalIsAskedForOnceMore() {
        QuestGenerator generator = generator(ZORN, HERBS);

        Quest quest = generator.generate(brann);

        assertFalse(generator.isFallbackUsed());
        assertEquals("Herbs for the Forge", quest.getTitle());
    }

    @Test
    void twoInvalidProposalsFallBackToATemplateQuest() {
        QuestGenerator generator = generator(ZORN, ZORN);

        Quest quest = generator.generate(brann);

        assertTrue(generator.isFallbackUsed());
        assertEquals("invalid output", generator.getFallbackReason());
        assertEquals("Find the shed key", quest.getTitle(), "the first unowned item outside the Forge");
        assertEquals(ObjectiveType.FETCH, quest.getObjectives().get(0).getType());
        assertEquals("shed key", quest.getObjectives().get(0).getTargetId());
        assertEquals(QuestGenerator.FETCH_GOLD, quest.getReward().getGold());
        assertEquals("blacksmith", quest.getGiverId());
    }

    @Test
    void aClientThatThrowsFallsBackWithoutCrashing() {
        QuestGenerator generator = generator(new LLMClient() {
            @Override
            public LlmResponse complete(LlmRequest request) {
                throw new IllegalStateException("boom");
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String providerName() {
                return "broken";
            }
        });

        Quest quest = generator.generate(brann);

        assertNotNull(quest);
        assertTrue(generator.isFallbackUsed());
        assertEquals("LLM unavailable: client error", generator.getFallbackReason());
    }

    @Test
    void offlineEveryOfferIsATemplateQuest() {
        QuestGenerator generator = generator();

        Quest quest = generator.generate(brann);

        assertNotNull(quest);
        assertTrue(generator.isFallbackUsed());
        assertEquals("LLM unavailable", generator.getFallbackReason());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not json at all",
            "[1, 2, 3]",
            // an objective type the game does not have
            "{\"title\":\"T\",\"description\":\"D\",\"objectives\":[{\"type\":\"escort\",\"target\":\"Marta\"}],"
                    + "\"reward\":{\"gold\":1,\"xp\":1,\"item\":null}}",
            // fields that try to set game state
            "{\"title\":\"T\",\"description\":\"D\",\"status\":\"COMPLETED\",\"objectives\":[{\"type\":\"visit\","
                    + "\"target\":\"old-mill\"}],\"reward\":{\"gold\":1,\"xp\":1,\"item\":null}}",
            "{\"title\":\"T\",\"description\":\"D\",\"objectives\":[{\"type\":\"visit\",\"target\":\"old-mill\","
                    + "\"progress\":1}],\"reward\":{\"gold\":1,\"xp\":1,\"item\":null}}",
            "{\"title\":\"T\",\"description\":\"D\",\"objectives\":[{\"type\":\"visit\",\"target\":\"old-mill\"}],"
                    + "\"reward\":{\"gold\":1,\"xp\":1,\"item\":null,\"hp\":100}}",
            "{\"title\":\"T\",\"description\":\"D\",\"giver\":{\"name\":\"Brann\"},\"objectives\":[{\"type\":"
                    + "\"visit\",\"target\":\"old-mill\"}],\"reward\":{\"gold\":1,\"xp\":1,\"item\":null}}",
            // numbers that are not whole and non-negative
            "{\"title\":\"T\",\"description\":\"D\",\"objectives\":[{\"type\":\"visit\",\"target\":\"old-mill\"}],"
                    + "\"reward\":{\"gold\":-5,\"xp\":1,\"item\":null}}",
            "{\"title\":\"T\",\"description\":\"D\",\"objectives\":[{\"type\":\"visit\",\"target\":\"old-mill\"}],"
                    + "\"reward\":{\"gold\":\"lots\",\"xp\":1,\"item\":null}}",
            "{\"title\":\"T\",\"description\":\"D\",\"objectives\":[{\"type\":\"visit\",\"target\":\"old-mill\","
                    + "\"count\":1.5}],\"reward\":{\"gold\":1,\"xp\":1,\"item\":null}}",
            "{\"title\":\"T\",\"description\":\"D\",\"objectives\":[{\"type\":\"visit\",\"target\":\"old-mill\","
                    + "\"count\":0}],\"reward\":{\"gold\":1,\"xp\":1,\"item\":null}}",
            // missing parts
            "{\"title\":\"T\",\"description\":\"D\",\"objectives\":[{\"type\":\"visit\",\"target\":\"old-mill\"}]}",
            "{\"title\":\"T\",\"objectives\":[{\"type\":\"visit\",\"target\":\"old-mill\"}],"
                    + "\"reward\":{\"gold\":1,\"xp\":1,\"item\":null}}",
            "{\"title\":\"T\",\"description\":\"D\",\"objectives\":[{\"type\":\"visit\"}],"
                    + "\"reward\":{\"gold\":1,\"xp\":1,\"item\":null}}"})
    void anAnswerThatBreaksTheSchemaIsNeverUsed(String answer) {
        QuestGenerator generator = generator(answer, answer);

        Quest quest = generator.generate(brann);

        assertTrue(generator.isFallbackUsed(), answer);
        assertEquals("invalid output", generator.getFallbackReason());
        assertTrue(quest.getTitle().startsWith("Find the "), "a template instead: " + quest.getTitle());
    }

    @Test
    void theSameSchemaIsAcceptedWithAFenceAndAnyFieldCase() {
        QuestGenerator generator = generator("Here you go:\n```json\n{\"Title\":\"Scout\",\"DESCRIPTION\":\"Go look.\","
                + "\"Objectives\":[{\"Type\":\"VISIT\",\"Target\":\"bandit-camp\",\"Count\":1}],"
                + "\"Reward\":{\"Gold\":5,\"XP\":5,\"Item\":\"none\"},\"Giver\":\"Brann\"}\n```");

        Quest quest = generator.generate(brann);

        assertFalse(generator.isFallbackUsed());
        assertEquals("bandit-camp", quest.getObjectives().get(0).getTargetId());
        assertNull(quest.getReward().getItemName());
    }

    @Test
    void aRewardAboveTheLevelLimitIsClamped() {
        String greedy = "{\"title\":\"Scout\",\"description\":\"Go look.\",\"objectives\":[{\"type\":\"visit\","
                + "\"target\":\"bandit-camp\"}],\"reward\":{\"gold\":1000,\"xp\":500,\"item\":null}}";

        Quest atLevelOne = generator(greedy).generate(brann);
        assertEquals(QuestGenerator.REWARD_PER_LEVEL, atLevelOne.getReward().getGold());
        assertEquals(QuestGenerator.REWARD_PER_LEVEL, atLevelOne.getReward().getXp());

        state.getPlayer().addReward(new Reward(0, 100, null));
        Quest atLevelTwo = generator(greedy).generate(brann);
        assertEquals(2 * QuestGenerator.REWARD_PER_LEVEL, atLevelTwo.getReward().getGold());
        assertEquals(2 * QuestGenerator.REWARD_PER_LEVEL, atLevelTwo.getReward().getXp());
    }

    @Test
    void aProposalNamingAnotherGiverIsRejected() {
        String fromMarta = "{\"title\":\"Scout\",\"description\":\"Go look.\",\"giver\":\"Marta\",\"objectives\":"
                + "[{\"type\":\"visit\",\"target\":\"bandit-camp\"}],\"reward\":{\"gold\":5,\"xp\":5,\"item\":null}}";
        QuestGenerator generator = generator(fromMarta, fromMarta);

        generator.generate(brann);

        assertTrue(generator.isFallbackUsed());
    }

    @Test
    void thePromptHoldsTheWorldTheGiverAndTheLimitsButNoSecrets() {
        RecordingClient client = new RecordingClient(HERBS);

        generator(client).generate(brann);

        LlmRequest request = client.requests.get(0);
        assertTrue(request.isJsonMode());
        String system = request.getSystemPrompt();
        String user = request.getUserPrompt();
        assertTrue(system.contains("Brann, the blacksmith"), system);
        assertTrue(system.contains("at most 25 gold and 25 XP"), system);
        assertTrue(system.contains("iron key (0.2 kg), warhammer (8.0 kg)"), system);
        assertTrue(system.contains("1 to 3 objectives"), system);
        assertTrue(user.contains("- Old Mill [old-mill]; items: healing herbs"), user);
        assertTrue(user.contains("level 1, now at Blacksmith's Forge [forge]"), user);
        assertTrue(user.contains("already has (do not repeat them): none"), user);
        for (String secret : brann.getSecrets()) {
            assertFalse(system.contains(secret) || user.contains(secret), "secret leaked: " + secret);
        }
    }

    @Test
    void everyTemplateQuestIsValidInTheDefaultWorld() {
        QuestGenerator generator = generator();
        QuestValidator validator = new QuestValidator(state);

        List<QuestProposal> templates = generator.templates(brann);

        assertEquals(List.of(ObjectiveType.FETCH, ObjectiveType.DEFEAT, ObjectiveType.VISIT),
                templates.stream().map(template -> template.getObjectives().get(0).getType()).toList());
        for (QuestProposal template : templates) {
            assertTrue(validator.validate(template, brann).isValid(), template.getTitle());
        }
        assertEquals("Deal with the wolf", templates.get(1).getTitle());
        assertEquals(new Reward(QuestGenerator.DEFEAT_GOLD, QuestGenerator.DEFEAT_XP, "iron key").describe(),
                templates.get(1).getReward().describe(), "the giver's lightest item");
        assertEquals("Scout the Tavern", templates.get(2).getTitle(), "the first location not visited yet");
    }

    @Test
    void theTemplatesTakeTurnsAfterEachQuestFromTheGiver() {
        Quest first = generator().generate(brann);
        state.getQuestLog().accept(first);
        first.complete();

        Quest second = generator().generate(brann);

        assertEquals(ObjectiveType.DEFEAT, second.getObjectives().get(0).getType(), "after one quest from Brann");
    }

    @Test
    void templatesSkipWhatNoLongerExists() {
        Location den = state.getWorld().findLocation("wolf-den");
        den.removeEnemy(den.findEnemy("wolf"));

        List<QuestProposal> templates = generator().templates(brann);

        assertEquals("Deal with the bandit leader", templates.get(1).getTitle());
    }

    @Test
    void noQuestWhenNoValidQuestIsPossible() {
        for (String id : List.of("a", "b", "c")) {
            state.getQuestLog().accept(new Quest(id, "Quest " + id, "Busy.", "someone", false,
                    List.of(new Objective(ObjectiveType.VISIT, "wolf-den", 1)), new Reward(1, 1, null)));
        }
        QuestGenerator generator = generator(HERBS, HERBS);

        assertNull(generator.generate(brann), "the quest log is full");
        assertTrue(generator.isFallbackUsed());

        NPC marta = state.getWorld().findLocation("tavern").findNpc("Marta");
        state.setMode(new DialogueMode(marta));
        assertNull(generator().generate(marta), "Marta gives no quests");
    }
}
