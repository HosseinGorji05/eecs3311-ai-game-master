package agent;

import engine.character.CharacterClass;
import engine.character.CharacterFactory;
import engine.character.Stats;
import engine.core.DialogueMode;
import engine.core.GameState;
import engine.items.Item;
import engine.quests.Objective;
import engine.quests.ObjectiveType;
import engine.quests.Quest;
import engine.quests.QuestStatus;
import engine.quests.Reward;
import engine.world.Location;
import engine.world.NPC;
import engine.world.Region;
import engine.world.World;
import llm.ScriptedLLMClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Quest replies in the shapes real models write (F07): what is now read tolerantly, what is still refused,
 * the main quest's reserved objectives, and the exact rule the debug output names. Replies are scripted.
 */
class QuestReplyShapesTest {

    private GameState state;
    private NPC brann;
    private final ByteArrayOutputStream debug = new ByteArrayOutputStream();

    @BeforeEach
    void talkToBrannWithTheMainQuestActive() {
        state = new GameState();
        state.start(CharacterFactory.forClass(CharacterClass.WARRIOR).createCharacter("Ari", new Stats(5, 3, 2)),
                World.createDefault());
        state.getQuestLog().accept(new Quest("main-quest", "Recover the village relic", "Take it back.", null, true,
                List.of(new Objective(ObjectiveType.DEFEAT, World.BANDIT_LEADER, 1),
                        new Objective(ObjectiveType.FETCH, World.RELIC, 1)),
                new Reward(50, 50, null)));
        state.setCurrentLocation(state.getWorld().findLocation("forge"));
        brann = state.currentLocation().findNpc("Brann");
        state.setMode(new DialogueMode(brann));
    }

    /** Asks for a quest; the model answers {@code reply} on both attempts. */
    private Quest ask(String reply, QuestGenerator[] used) {
        QuestGenerator generator = new QuestGenerator(new ScriptedLLMClient(List.of(reply, reply)), new PromptBuilder(),
                new ResponseParser(), state);
        generator.setDebugOutput(new PrintStream(debug, true, StandardCharsets.UTF_8));
        used[0] = generator;
        return generator.generate(brann);
    }

    private Quest accepted(String reply) {
        QuestGenerator[] used = new QuestGenerator[1];
        Quest quest = ask(reply, used);
        assertFalse(used[0].isFallbackUsed(), "the model's quest was used; debug:\n" + debugText());
        return quest;
    }

    private String refusedBecause(String reply) {
        QuestGenerator[] used = new QuestGenerator[1];
        Quest quest = ask(reply, used);
        assertTrue(used[0].isFallbackUsed(), "the reply was used: " + reply);
        assertEquals("invalid output", used[0].getFallbackReason());
        assertNotNull(quest, "a template quest instead");
        return debugText();
    }

    private String debugText() {
        return debug.toString(StandardCharsets.UTF_8);
    }

    @Test
    void aFencedReplyWithTalkAroundItAndAnyFieldCaseIsRead() {
        Quest quest = accepted("""
                Sure! Here is a quest for Brann:
                ```json
                {"Title": "Bread for the forge", "Description": "Bring me bread from the tavern.",
                 "Objectives": [{"Type": "FETCH", "Target": "Bread", "Count": 1}],
                 "Reward": {"Gold": 10, "XP": 10, "Item": null}}
                ```
                Let me know if you want another.""");

        assertEquals("Bread for the forge", quest.getTitle());
        assertEquals("bread", quest.getObjectives().get(0).getTargetId(), "the game's own name");
    }

    @Test
    void extraFieldsAreIgnoredAndCanSetNothing() {
        Quest quest = accepted("""
                {"id":"q-99","title":"Scout the mill","description":"Go and look at the old mill.","status":"COMPLETED",
                 "difficulty":"easy","objectives":[{"type":"visit","target":"old-mill","count":1,"progress":1,
                 "description":"walk there"}],"reward":{"gold":10,"xp":10,"item":null,"hp":100}}""");

        assertEquals(QuestStatus.ACTIVE, quest.getStatus());
        assertEquals(0, quest.getObjectives().get(0).getProgress());
        assertEquals("blacksmith:scout-the-mill", quest.getId(), "the id is the game's, not the reply's");
    }

    @Test
    void aWrappedQuestAOneElementArrayAndASingleObjectiveObjectAreRead() {
        assertEquals("Herbs", accepted("""
                {"quest":{"title":"Herbs","description":"Pick the herbs by the mill.",
                 "objectives":{"type":"fetch","target":"healing herbs"},"reward":{"gold":5,"xp":5}}}""").getTitle());
        assertEquals("Herbs", accepted("""
                [{"title":"Herbs","description":"Pick the herbs by the mill.",
                  "objectives":[{"type":"fetch","target":"healing herbs"}],"reward":{"gold":5,"xp":5,"item":null}}]""")
                .getTitle());
    }

    @Test
    void commonFieldNamesNumbersAsTextAndAMissingAmountAreRead() {
        Quest quest = accepted("""
                {"name":"Wolf trouble","desc":"The wolf must go.",
                 "objectives":[{"kind":"Defeat","enemy":"wolf","quantity":"1"}],
                 "rewards":{"coins":"20","experience":15}}""");

        assertEquals("Wolf trouble", quest.getTitle());
        assertEquals(ObjectiveType.DEFEAT, quest.getObjectives().get(0).getType());
        assertEquals("wolf", quest.getObjectives().get(0).getTargetId());
        assertEquals(20, quest.getReward().getGold());
        assertEquals(15, quest.getReward().getXp());

        Quest noXp = accepted("""
                {"title":"Scout","description":"Look around the mill.",
                 "objectives":[{"type":"visit","location":"Old Mill"}],"reward":{"gold":5}}""");
        assertEquals(0, noXp.getReward().getXp());
        assertEquals("old-mill", noXp.getObjectives().get(0).getTargetId(), "a display name becomes the id");
    }

    @Test
    void namesWithAnArticleOrCopiedDetailsAreTheRealThings() {
        Quest quest = accepted("""
                {"title":"Keys and herbs","description":"Fetch these for me.","giver":"Brann the blacksmith",
                 "objectives":[{"type":"fetch","target":"the  Shed Key","count":1},
                               {"type":"visit","target":"Old Mill [old-mill]","count":1}],
                 "reward":{"gold":10,"xp":10,"item":"iron key (0.2 kg)"}}""");

        assertEquals("shed key", quest.getObjectives().get(0).getTargetId());
        assertEquals("old-mill", quest.getObjectives().get(1).getTargetId());
        assertEquals("iron key", quest.getReward().getItemName());
    }

    @Test
    void aTooLargeRewardIsClampedNotRefused() {
        Quest quest = accepted("""
                {"title":"Rich","description":"Go to the mill.","objectives":[{"type":"visit","target":"old-mill"}],
                 "reward":{"gold":1000,"xp":"500","item":null}}""");

        assertEquals(QuestGenerator.REWARD_PER_LEVEL, quest.getReward().getGold());
        assertEquals(QuestGenerator.REWARD_PER_LEVEL, quest.getReward().getXp());
    }

    @Test
    void aSideQuestMayNotUseTheMainQuestsObjectives() {
        String bandit = refusedBecause("""
                {"title":"End the bandits","description":"Kill their leader.",
                 "objectives":[{"type":"defeat","target":"Bandit Leader"}],"reward":{"gold":20,"xp":20,"item":null}}""");
        assertTrue(bandit.contains("MAIN_QUEST: defeat bandit leader is an objective of the main quest Recover the village relic"),
                bandit);

        debug.reset();
        String relic = refusedBecause("""
                {"title":"The relic","description":"Bring back the relic.",
                 "objectives":[{"type":"fetch","target":"the village relic"}],"reward":{"gold":20,"xp":20,"item":null}}""");
        assertTrue(relic.contains("MAIN_QUEST: fetch village relic"), relic);
    }

    @Test
    void visitingTheBanditCampIsStillAllowedBecauseItDoesNotAdvanceTheMainQuest() {
        Quest quest = accepted("""
                {"title":"Spy on the camp","description":"Look at the bandit camp.",
                 "objectives":[{"type":"visit","target":"bandit-camp"}],"reward":{"gold":10,"xp":10,"item":null}}""");

        assertEquals("bandit-camp", quest.getObjectives().get(0).getTargetId());
    }

    @Test
    void theTemplatesNeverPickTheMainQuestsTargets() {
        // Only the relic is left to fetch and only the bandit leader to defeat.
        for (Region region : state.getWorld().getRegions()) {
            for (Location location : region.getLocations()) {
                for (Item item : new ArrayList<>(location.getItems())) {
                    if (!item.getName().equals(World.RELIC) && item.getOwnerId() == null) {
                        location.removeItem(item.getName());
                    }
                }
                location.getEnemies().stream().filter(enemy -> enemy.getName().equals("wolf")).toList()
                        .forEach(location::removeEnemy);
            }
        }
        QuestGenerator generator = new QuestGenerator(new ScriptedLLMClient(List.of()), new PromptBuilder(),
                new ResponseParser(), state);

        List<QuestProposal> templates = generator.templates(brann);

        assertEquals(1, templates.size(), "no fetch or defeat template is left, only visit");
        assertEquals(ObjectiveType.VISIT, templates.get(0).getObjectives().get(0).getType());
    }

    @Test
    void theSafetyRulesStillRefuseAndTheDebugOutputNamesTheRule() {
        assertTrue(refusedBecause("""
                {"title":"Zorn","description":"Find the crystal.","objectives":[{"type":"fetch","target":"Crystal of Zorn"}],
                 "reward":{"gold":10,"xp":10,"item":null}}""").contains("TARGET_EXISTS: there is no fetch Crystal of Zorn"));
        debug.reset();
        assertTrue(refusedBecause("""
                {"title":"Too much","description":"Many things.","objectives":[{"type":"visit","target":"old-mill"},
                 {"type":"visit","target":"tavern"},{"type":"visit","target":"wolf-den"},{"type":"defeat","target":"wolf"}],
                 "reward":{"gold":10,"xp":10,"item":null}}""").contains("OBJECTIVE_COUNT: a quest needs 1-3 objectives, not 4"));
        debug.reset();
        assertTrue(refusedBecause("""
                {"title":"Escort","description":"Walk Marta home.","objectives":[{"type":"escort","target":"Marta"}],
                 "reward":{"gold":10,"xp":10,"item":null}}""").contains("OBJECTIVE_TYPE: \"escort\" is not fetch, defeat or visit"));
        debug.reset();
        assertTrue(refusedBecause("""
                {"title":"Steal","description":"Take the warhammer.","objectives":[{"type":"fetch","target":"warhammer"}],
                 "reward":{"gold":10,"xp":10,"item":null}}""").contains("TARGET_AVAILABLE: the warhammer belongs to Brann"));
        debug.reset();
        assertTrue(refusedBecause("""
                {"title":"Bad","description":"x","objectives":[{"type":"visit","target":"old-mill","count":"two"}],
                 "reward":{"gold":10,"xp":10,"item":null}}""").contains("OBJECTIVE_COUNT: count \"two\" is not a whole number"));
        debug.reset();
        assertTrue(refusedBecause("""
                {"title":"Bad","description":"x","objectives":[{"type":"visit","target":"old-mill"}],
                 "reward":{"gold":-5,"xp":10,"item":null}}""").contains("REWARD_AMOUNT: gold -5 is not a whole number of at least 0"));
        debug.reset();
        assertTrue(refusedBecause("Sorry, I cannot think of a quest.").contains("JSON: the reply holds no JSON object"));
        debug.reset();
        assertTrue(refusedBecause("""
                {"description":"x","objectives":[{"type":"visit","target":"old-mill"}],"reward":{"gold":1,"xp":1}}""")
                .contains("TITLE: the title is missing or not text"));
    }

    @Test
    void theDebugOutputNamesTheRuleOnEveryAttemptAndHoldsNoKey() {
        refusedBecause("""
                {"title":"Escort","description":"x","objectives":[{"type":"escort","target":"Marta"}],"reward":{"gold":1,"xp":1}}""");

        String text = debugText();
        assertTrue(text.contains("QuestGenerator attempt 1/2 failed: invalid output - OBJECTIVE_TYPE:"), text);
        assertTrue(text.contains("QuestGenerator attempt 2/2 failed: invalid output - OBJECTIVE_TYPE:"), text);
        assertFalse(text.toLowerCase().contains("authorization") || text.contains("sk-") || text.contains("gsk_"), text);
    }

    @Test
    void normalizedNamesDropArticlesDetailsAndExtraSpaces() {
        assertEquals("shed key", QuestValidator.normalizeName("  the   shed key "));
        assertEquals("iron key", QuestValidator.normalizeName("iron key (Brann's)"));
        assertEquals("Old Mill", QuestValidator.normalizeName("Old Mill [old-mill]"));
        assertEquals("iron key", QuestValidator.normalizeName("An iron key (0.2 kg) (Brann's)"));
        assertEquals("the", QuestValidator.normalizeName("the"), "nothing would be left, so the word stays");
    }
}
