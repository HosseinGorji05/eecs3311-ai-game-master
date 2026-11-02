package presentation;

import engine.character.CharacterClass;
import engine.character.Player;
import engine.character.Stats;
import engine.commands.ActionProposal;
import engine.commands.ActionType;
import engine.core.DialogueMode;
import engine.core.FixedDiceRoller;
import engine.core.GameState;
import engine.events.EventType;
import engine.events.EventVerb;
import engine.events.GameEvent;
import engine.events.GameEventListener;
import engine.quests.Objective;
import engine.quests.ObjectiveType;
import engine.quests.Quest;
import engine.quests.QuestStatus;
import engine.quests.Reward;
import llm.ScriptedLLMClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Quest offers through the controller (SD07, UC07) and quests played to completion with the real
 * engine (SD05, UC08): Ari the warrior walks to the Forge and talks to Brann. Moves, talking and
 * leaving are plain actions, so the scripted model answers are only the NPC's lines and the quest
 * proposals, in the order the controller asks for them.
 */
class GameControllerQuestTest {

    private static final String REPLY = "{\"text\":\"Work? Maybe.\",\"sideEffect\":null}";
    private static final String HERBS = "{\"title\":\"Herbs for the Forge\",\"description\":\"Fetch the herbs "
            + "from the Old Mill.\",\"objectives\":[{\"type\":\"fetch\",\"target\":\"healing herbs\",\"count\":1}],"
            + "\"reward\":{\"gold\":20,\"xp\":15,\"item\":\"iron key\"}}";
    private static final String WOLF = "{\"title\":\"The Wolf\",\"description\":\"Kill the wolf in its den.\","
            + "\"objectives\":[{\"type\":\"defeat\",\"target\":\"wolf\",\"count\":1}],"
            + "\"reward\":{\"gold\":25,\"xp\":25,\"item\":\"iron key\"}}";

    /** Records everything the controller shows, the quests it offers and every published event. */
    private static final class RecordingView implements GameView, GameEventListener {
        final List<String> shown = new ArrayList<>();
        final List<Quest> offers = new ArrayList<>();
        final List<GameEvent> events = new ArrayList<>();

        @Override
        public void render(GameState state) {
        }

        @Override
        public void showNarration(String text) {
            shown.add("narration: " + text);
        }

        @Override
        public void showNotice(String text) {
            shown.add("notice: " + text);
        }

        @Override
        public void showError(String message) {
            shown.add("error: " + message);
        }

        @Override
        public void showQuestOffer(Quest quest) {
            offers.add(quest);
            shown.add("offer: " + quest.getTitle());
        }

        @Override
        public void onEvent(GameEvent event) {
            events.add(event);
        }
    }

    private final RecordingView view = new RecordingView();
    private GameController controller;

    /** A started game with these model answers and dice, Ari standing in the Village Square. */
    private GameController start(FixedDiceRoller dice, String... llmResponses) {
        controller = new GameController(new ScriptedLLMClient(List.of(llmResponses)), dice);
        controller.newGame("Ari", CharacterClass.WARRIOR, new Stats(5, 3, 2));
        controller.startGame();
        controller.addView(view);
        return controller;
    }

    private void act(ActionType type, String target) {
        TurnResult turn = controller.performAction(new ActionProposal(type, target, null));
        assertTrue(turn.getResults().stream().allMatch(result -> result.isSuccess()), turn.getNarration());
    }

    /** Walks west to the Forge and starts talking to Brann; what the view showed so far is forgotten. */
    private void talkToBrann() {
        act(ActionType.MOVE, "west");
        act(ActionType.TALK, "Brann");
        view.shown.clear();
    }

    private GameState state() {
        return controller.getState();
    }

    private List<GameEvent> questEvents() {
        return view.events.stream().filter(event -> event.getType() == EventType.QUEST).toList();
    }

    private void fillQuestLog() {
        for (String id : List.of("a", "b", "c")) {
            state().getQuestLog().accept(new Quest(id, "Errand " + id, "Someone else's errand.", "someone", false,
                    List.of(new Objective(ObjectiveType.VISIT, "wolf-den", 1)), new Reward(1, 1, null)));
        }
    }

    // The offer

    @Test
    void brannOffersTheModelsQuestAfterHisFirstReply() {
        start(new FixedDiceRoller(), REPLY, HERBS);
        talkToBrann();
        int turn = state().getTurn();

        controller.sendDialogue("Do you have any work for me?");

        assertEquals(List.of("narration: Brann: \"Work? Maybe.\"", "offer: Herbs for the Forge"), view.shown);
        assertTrue(((DialogueMode) state().getMode()).isQuestOffered());
        assertTrue(state().getQuestLog().allQuests().isEmpty(), "offered, not accepted");
        assertEquals(turn, state().getTurn(), "neither the line nor the offer uses up a turn");
    }

    @Test
    void anNpcOffersAtMostOneQuestPerConversation() {
        start(new FixedDiceRoller(), REPLY, HERBS, "{\"text\":\"Well?\",\"sideEffect\":null}");
        talkToBrann();

        controller.sendDialogue("Any work?");
        controller.sendDialogue("Anything else?");

        assertEquals(1, view.offers.size());
        assertEquals("narration: Brann: \"Well?\"", view.shown.get(view.shown.size() - 1),
                "the third answer went to the dialogue, not to another quest");
    }

    @Test
    void offlineBrannOffersATemplateQuestWithANotice() {
        start(new FixedDiceRoller());
        talkToBrann();

        controller.sendDialogue("Any work?");

        assertTrue(view.shown.contains("notice: Brann offers a pre-written quest. Reason: LLM unavailable."), view.shown.toString());
        assertEquals(1, view.offers.size());
        assertEquals("Find the shed key", view.offers.get(0).getTitle());
    }

    @Test
    void martaOffersNothing() {
        start(new FixedDiceRoller(), REPLY, "{\"text\":\"More ale?\",\"sideEffect\":null}");
        act(ActionType.MOVE, "north");
        act(ActionType.TALK, "Marta");

        controller.sendDialogue("Any work?");
        controller.sendDialogue("Anything at all?");

        assertTrue(view.offers.isEmpty());
        assertFalse(((DialogueMode) state().getMode()).isQuestOffered());
        assertEquals("narration: Marta: \"More ale?\"", view.shown.get(view.shown.size() - 1),
                "no answer was spent on a quest");
    }

    @Test
    void noOfferWhileThePlayerHasAnOpenQuestFromBrannOrAFullLog() {
        start(new FixedDiceRoller(), REPLY, HERBS, REPLY, REPLY);
        talkToBrann();
        controller.sendDialogue("Any work?");
        controller.respondToQuest(view.offers.get(0), true);
        act(ActionType.LEAVE, null);
        act(ActionType.TALK, "Brann");

        controller.sendDialogue("More work?");
        assertEquals(1, view.offers.size(), "Brann's quest is still open");

        GameController other = start(new FixedDiceRoller(), REPLY, HERBS);
        view.offers.clear();
        fillQuestLog();
        talkToBrann();
        other.sendDialogue("Any work?");
        assertTrue(view.offers.isEmpty(), "three side quests are active");
    }

    @Test
    void aReplyThatEndsTheConversationOffersNothing() {
        start(new FixedDiceRoller(), "{\"text\":\"Out of my forge.\",\"sideEffect\":{\"type\":\"leave\"}}", HERBS);
        talkToBrann();

        controller.sendDialogue("Give me gold.");

        assertTrue(view.offers.isEmpty());
        assertFalse(state().getMode() instanceof DialogueMode);
    }

    // Accept and decline

    @Test
    void acceptingAddsTheQuestAndPublishesAQuestEvent() {
        start(new FixedDiceRoller(), REPLY, HERBS);
        talkToBrann();
        controller.sendDialogue("Any work?");
        Quest offer = view.offers.get(0);
        int turn = state().getTurn();

        controller.respondToQuest(offer, true);

        assertEquals(List.of(offer), state().getQuestLog().activeQuests());
        assertTrue(state().getQuestLog().hasQuestFrom(state().getWorld().findNpc("blacksmith")));
        GameEvent accepted = questEvents().get(0);
        assertEquals(EventVerb.ACCEPTED, accepted.getVerb());
        assertEquals("Quest accepted: Herbs for the Forge", accepted.getDescription());
        assertEquals("blacksmith:herbs-for-the-forge", accepted.getSubjectId());
        assertEquals(0, accepted.getAlignmentDelta());
        assertEquals(turn, state().getTurn());

        controller.respondToQuest(offer, true);
        assertEquals("error: " + GameController.NO_OFFER, view.shown.get(view.shown.size() - 1), "the offer is closed");
        assertEquals(1, state().getQuestLog().allQuests().size());
    }

    @Test
    void decliningDiscardsTheQuestAndBrannOffersAgainOnlyInALaterConversation() {
        start(new FixedDiceRoller(), REPLY, HERBS, REPLY, REPLY, WOLF);
        talkToBrann();
        controller.sendDialogue("Any work?");

        controller.respondToQuest(view.offers.get(0), false);

        assertTrue(state().getQuestLog().allQuests().isEmpty());
        assertEquals("narration: You decline \"Herbs for the Forge\".", view.shown.get(view.shown.size() - 1));
        assertTrue(questEvents().isEmpty());
        controller.sendDialogue("Changed my mind.");
        assertEquals(1, view.offers.size(), "no new offer in the same conversation");

        act(ActionType.LEAVE, null);
        act(ActionType.TALK, "Brann");
        controller.sendDialogue("Any work now?");
        assertEquals(2, view.offers.size(), "a later conversation may offer again");
        assertEquals("The Wolf", view.offers.get(1).getTitle());
    }

    @Test
    void onlyTheQuestOnOfferInThisConversationCanBeAnswered() {
        start(new FixedDiceRoller(), REPLY, HERBS);
        talkToBrann();
        controller.sendDialogue("Any work?");
        Quest offer = view.offers.get(0);
        Quest forged = new Quest("x", "Free Gold", "Nothing to do.", "blacksmith", false,
                List.of(new Objective(ObjectiveType.VISIT, "tavern", 1)), new Reward(999, 999, null));

        controller.respondToQuest(null, true);
        controller.respondToQuest(forged, true);
        act(ActionType.LEAVE, null);
        controller.respondToQuest(offer, true);

        assertEquals(List.of("error: " + GameController.NO_OFFER, "error: " + GameController.NO_OFFER,
                "error: " + GameController.NO_OFFER), view.shown.stream().filter(line -> line.startsWith("error")).toList());
        assertTrue(state().getQuestLog().allQuests().isEmpty());
    }

    @Test
    void acceptingWhenTheLogFilledUpMeanwhileShowsAnError() {
        start(new FixedDiceRoller(), REPLY, HERBS);
        talkToBrann();
        controller.sendDialogue("Any work?");
        fillQuestLog();

        controller.respondToQuest(view.offers.get(0), true);

        assertEquals("error: Your quest log is full.", view.shown.get(view.shown.size() - 1));
        assertFalse(state().getQuestLog().allQuests().contains(view.offers.get(0)));
    }

    // Played to completion with the real engine

    @Test
    void aFetchQuestCompletesOnceAndGrantsGoldXpAndItsItemOnce() {
        start(new FixedDiceRoller(), REPLY, HERBS);
        talkToBrann();
        controller.sendDialogue("Any work?");
        Quest quest = view.offers.get(0);
        controller.respondToQuest(quest, true);
        act(ActionType.LEAVE, null);
        act(ActionType.MOVE, "east");
        act(ActionType.MOVE, "south");
        act(ActionType.MOVE, "east");
        Player ari = state().getPlayer();

        act(ActionType.TAKE, "healing herbs");

        assertEquals(QuestStatus.COMPLETED, quest.getStatus());
        assertEquals("1/1", quest.getObjectives().get(0).getProgress() + "/" + quest.getObjectives().get(0).getRequired());
        assertEquals(20, ari.getGold());
        assertEquals(15, ari.getXp());
        assertNull(ari.getInventory().find("iron key").getOwnerId(), "Brann handed the iron key over");
        assertNull(state().getWorld().findLocation("forge").findItem("iron key"));
        GameEvent completed = questEvents().get(1);
        assertEquals(EventVerb.COMPLETED, completed.getVerb());
        assertEquals(2, completed.getAlignmentDelta());
        assertEquals("Quest completed: Herbs for the Forge. Reward: 20 gold, 15 XP, the iron key. "
                + "Brann gives you the iron key.", completed.getDescription());

        act(ActionType.DROP, "healing herbs");
        act(ActionType.TAKE, "healing herbs");
        assertEquals(20, ari.getGold(), "never rewarded twice");
        assertEquals(15, ari.getXp());
        assertEquals(2, questEvents().size(), "accepted and completed, once each");
    }

    @Test
    void aDefeatQuestCompletesThroughCombatAndGrantsItsRewardOnce() {
        // Ari hits the wolf (15 + 5 vs 11) for 6, the wolf misses (1 + 3 vs 13), Ari hits again: dead.
        start(new FixedDiceRoller(15, 1, 15), REPLY, WOLF);
        talkToBrann();
        controller.sendDialogue("Any work?");
        Quest quest = view.offers.get(0);
        controller.respondToQuest(quest, true);
        act(ActionType.LEAVE, null);
        act(ActionType.EQUIP, "sword");
        act(ActionType.MOVE, "east");
        act(ActionType.MOVE, "south");
        act(ActionType.MOVE, "west");

        act(ActionType.ATTACK, "wolf");
        assertEquals(QuestStatus.ACTIVE, quest.getStatus(), "wounding is not defeating");
        act(ActionType.ATTACK, "wolf");

        Player ari = state().getPlayer();
        assertEquals(QuestStatus.COMPLETED, quest.getStatus());
        assertEquals(25, ari.getGold());
        assertEquals(25, ari.getXp());
        assertNotNull(ari.getInventory().find("iron key"), "the iron key fits (3.5 + 0.2 kg)");
        assertNull(ari.getInventory().find("iron key").getOwnerId());
        assertEquals(List.of(EventVerb.ACCEPTED, EventVerb.COMPLETED), questEvents().stream().map(GameEvent::getVerb).toList());
        assertEquals("wolf", quest.getObjectives().get(0).getTargetId());
    }
}
