package presentation;

import agent.NpcReply;
import engine.character.CharacterClass;
import engine.commands.ActionProposal;
import engine.core.FixedDiceRoller;
import engine.events.EventType;
import engine.events.GameEvent;
import engine.persistence.SaveManager;
import engine.quests.Quest;
import llm.ScriptedLLMClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JButton;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The dialogue panel with the quest card, the combat panel, the event log and the journal, driven through
 * the real controller (offline scripted client, scripted dice, saves in a temporary folder) with every
 * gameplay call recorded. The window is never shown. Skipped, not failed, without a display.
 */
@Timeout(60)
class SwingGameViewPanelsTest {

    /** The real controller, recording the calls the panels make. */
    private static final class RecordingController extends GameController {
        final List<String> calls = new CopyOnWriteArrayList<>();

        RecordingController(Path saves, int... rolls) {
            super(new ScriptedLLMClient(List.of()), new FixedDiceRoller(rolls), new SaveManager(saves));
        }

        @Override
        public TurnResult submitCommand(String text) {
            calls.add("submitCommand " + text);
            return super.submitCommand(text);
        }

        @Override
        public TurnResult performAction(ActionProposal proposal) {
            calls.add("performAction " + proposal.getType() + (proposal.getTarget() == null ? "" : " " + proposal.getTarget()));
            return super.performAction(proposal);
        }

        @Override
        public NpcReply sendDialogue(String message) {
            calls.add("sendDialogue " + message);
            return super.sendDialogue(message);
        }

        @Override
        public void respondToQuest(Quest quest, boolean accept) {
            calls.add("respondToQuest " + accept + (quest == null ? " (none)" : ""));
            super.respondToQuest(quest, accept);
        }

        @Override
        public void loadGame(String slot) {
            calls.add("loadGame " + slot);
            super.loadGame(slot);
        }
    }

    @TempDir
    Path saves;

    private RecordingController controller;
    private SwingGameView view;

    /** Creates the view; skipped without a display. Only the wolf fight rolls the dice. */
    private void open(int... rolls) throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        controller = new RecordingController(saves, rolls);
        view = onEdt(() -> new SwingGameView(controller));
        controller.addView(view);
        edt(() -> {
            view.nameField().setText("Ari");
            view.classBox().setSelectedItem(CharacterClass.ROGUE);
            List<JSlider> sliders = view.statSliders();
            sliders.get(0).setValue(2);
            sliders.get(1).setValue(6);
            sliders.get(2).setValue(2);
            view.startButton().doClick();
        });
        controller.calls.clear();
    }

    @AfterEach
    void close() throws Exception {
        if (view != null) {
            SwingUtilities.invokeAndWait(() -> {
                if (view.saveDialog() != null) {
                    view.saveDialog().dispose();
                }
                view.frame().dispose();
            });
        }
    }

    private static <T> T onEdt(Callable<T> task) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(task.call());
            } catch (Exception e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    private void edt(Runnable action) throws Exception {
        SwingUtilities.invokeAndWait(action);
        view.awaitIdle();
    }

    private void clickOnMap(String locationId) throws Exception {
        edt(() -> {
            MapPanel map = view.mapPanel();
            map.setSize(420, 330);
            Rectangle box = map.nodeBounds(map.getModel().find(locationId));
            map.dispatchEvent(new MouseEvent(map, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0,
                    (int) box.getCenterX(), (int) box.getCenterY(), 1, false, MouseEvent.BUTTON1));
        });
    }

    private void walk(String... locationIds) throws Exception {
        for (String id : locationIds) {
            clickOnMap(id);
        }
    }

    private void talkTo(String name) throws Exception {
        edt(() -> view.talkButtons().stream().filter(button -> button.getText().equals("Talk to " + name))
                .findFirst().orElseThrow().doClick());
    }

    private void sayInPanel(String line) throws Exception {
        edt(() -> {
            view.dialogueField().setText(line);
            view.dialogueSendButton().doClick();
        });
    }

    private void type(String line) throws Exception {
        edt(() -> {
            view.inputField().setText(line);
            view.sendButton().doClick();
        });
    }

    private void click(Callable<JButton> button) throws Exception {
        JButton target = onEdt(button);
        edt(target::doClick);
    }

    /** Talks to Brann in the Forge and asks for work, so he offers his (template) quest. */
    private void askBrannForWork() throws Exception {
        walk("forge");
        talkTo("Brann");
        sayInPanel("Do you have any work for me?");
        controller.calls.clear();
    }

    @Test
    void aTalkButtonOpensTheDialoguePanelWhoseSendAndLeaveReachTheController() throws Exception {
        open();
        walk("tavern");
        assertEquals("none", onEdt(view::modePanelName));
        controller.calls.clear();

        talkTo("Marta");
        assertEquals("dialogue", onEdt(view::modePanelName));
        assertEquals("Marta, the tavern keeper", onEdt(() -> view.dialogueHeading().getText()));
        assertFalse(onEdt(() -> view.talkButtons().get(0).isEnabled()), "no second conversation");
        sayInPanel("Hello there");
        String lines = onEdt(() -> view.conversationArea().getText());
        click(view::leaveButton);

        assertEquals(List.of("performAction TALK Marta", "sendDialogue Hello there", "performAction LEAVE"), controller.calls);
        assertTrue(lines.startsWith("You are talking to Marta (tavern keeper).\nYou: Hello there\nMarta: "), lines);
        assertEquals("none", onEdt(view::modePanelName));
        assertFalse(onEdt(() -> view.dialogueField().isEnabled()));
    }

    @Test
    void theStoryInputStillTalksAndLeavesAndItsLinesJoinTheConversation() throws Exception {
        open();
        walk("tavern");
        controller.calls.clear();
        type("talk to Marta");
        type("Any news?");
        String lines = onEdt(() -> view.conversationArea().getText());
        type("bye");

        assertEquals(List.of("submitCommand talk to Marta", "sendDialogue Any news?", "performAction LEAVE"),
                controller.calls);
        assertTrue(lines.contains("You: Any news?\nMarta: "), lines);
        assertEquals("none", onEdt(view::modePanelName));
    }

    @Test
    void theQuestCardsAcceptCallsRespondToQuestAndTheCardGoesAway() throws Exception {
        open();
        askBrannForWork();
        assertTrue(onEdt(() -> view.questCardPanel().isVisible()));
        assertTrue(onEdt(() -> view.questCardText().getText()).contains("Objectives:"));
        assertTrue(onEdt(() -> view.acceptButton().isEnabled()));

        click(view::acceptButton);

        assertEquals(List.of("respondToQuest true"), controller.calls);
        assertFalse(onEdt(() -> view.questCardPanel().isVisible()));
        assertFalse(onEdt(() -> view.acceptButton().isEnabled()));
        assertEquals(2, onEdt(() -> view.questList().getModel().getSize()), "the main quest and Brann's");
    }

    @Test
    void declineDiscardsTheOffer() throws Exception {
        open();
        askBrannForWork();

        click(view::declineButton);

        assertEquals(List.of("respondToQuest false"), controller.calls);
        assertFalse(onEdt(() -> view.questCardPanel().isVisible()));
        assertEquals(1, onEdt(() -> view.questList().getModel().getSize()));
    }

    @Test
    void typingAcceptStillAnswersTheCard() throws Exception {
        open();
        askBrannForWork();

        type("accept");

        assertEquals(List.of("respondToQuest true"), controller.calls);
        assertFalse(onEdt(() -> view.questCardPanel().isVisible()));
        assertEquals(2, onEdt(() -> view.questList().getModel().getSize()));
    }

    @Test
    void leavingTheConversationTakesTheCardAway() throws Exception {
        open();
        askBrannForWork();

        click(view::leaveButton);
        type("accept");

        assertFalse(onEdt(() -> view.questCardPanel().isVisible()));
        assertEquals(List.of("performAction LEAVE", "respondToQuest true (none)"), controller.calls);
        assertTrue(onEdt(() -> view.storyArea().getText()).contains("! " + GameController.NO_OFFER));
    }

    @Test
    void theCombatPanelShowsTheHpBarsAndTheRollsAndItsButtonsReachTheController() throws Exception {
        open(20, 1, 20, 1, 20);
        walk("forest-edge", "wolf-den");

        type("attack wolf");
        assertEquals("combat", onEdt(view::modePanelName));
        assertEquals("24 / 24 HP", onEdt(() -> view.playerHpBar().getString()));
        assertEquals(6, onEdt(() -> view.enemyHpBar().getValue()));
        assertEquals(8, onEdt(() -> view.enemyHpBar().getMaximum()));
        assertEquals("Ari rolled 20 + 2 = 22 against defense 11: hit, 2 damage (wolf 6/8 HP).\n"
                + "wolf rolled 1 + 3 = 4 against defense 16: miss.", onEdt(() -> view.diceArea().getText()));
        assertFalse(onEdt(() -> view.mapPanel().isMoveEnabled()));
        click(view::attackButton);
        assertEquals("4 / 8 HP", onEdt(() -> view.enemyHpBar().getString()));
        click(view::fleeButton);

        assertEquals(List.of("performAction MOVE Forest Edge", "performAction MOVE Wolf Den", "submitCommand attack wolf",
                "performAction ATTACK wolf", "performAction FLEE"), controller.calls);
        assertEquals("none", onEdt(view::modePanelName));
        assertFalse(onEdt(() -> view.attackButton().isEnabled()));
    }

    @Test
    void theJournalTabShowsGetJournalAfterEveryTurn() throws Exception {
        open();
        assertEquals(controller.getJournal(), onEdt(() -> view.journalArea().getText()), "after the start");

        walk("tavern");

        assertEquals(controller.getJournal(), onEdt(() -> view.journalArea().getText()));
        assertTrue(onEdt(() -> view.journalArea().getText()).contains("moved to Tavern"));
    }

    @Test
    void theEventLogIsNewestFirstAndItsFilterUsesGetHistory() throws Exception {
        open();
        walk("tavern", "village-square");
        type("look");

        List<String> newestFirst = new ArrayList<>(controller.getRecentHistory(EventTableModel.DEFAULT_CAP)
                .stream().map(GameEvent::getDescription).toList());
        Collections.reverse(newestFirst);
        assertEquals(newestFirst, onEdt(() -> column(3)));

        edt(() -> view.filterBox().setSelectedIndex(1));
        view.awaitIdle();

        assertEquals("Movement", onEdt(() -> view.filterBox().getSelectedItem().toString()));
        assertEquals(controller.getHistory(EventType.MOVEMENT).size(), onEdt(() -> view.eventTable().getRowCount()));
        assertTrue(onEdt(() -> column(2)).stream().allMatch("Movement"::equals));
        assertEquals("moved to Village Square", onEdt(() -> column(3).get(0)));
    }

    private List<String> column(int column) {
        List<String> values = new ArrayList<>();
        for (int row = 0; row < view.eventTable().getRowCount(); row++) {
            values.add(String.valueOf(view.eventTable().getValueAt(row, column)));
        }
        return values;
    }

    @Test
    void aGameSavedInAConversationLoadsBackIntoTheDialoguePanel() throws Exception {
        open();
        walk("tavern");
        talkTo("Marta");
        edt(() -> view.confirmAndSave("talking", List.of(), true));
        click(view::leaveButton);
        assertEquals("none", onEdt(view::modePanelName));

        edt(() -> view.loadSlot("talking"));

        assertEquals("dialogue", onEdt(view::modePanelName));
        assertEquals(ConversationLog.CONTINUED, onEdt(() -> view.conversationArea().getText()));
        assertEquals("Marta, the tavern keeper", onEdt(() -> view.dialogueHeading().getText()));
        assertTrue(onEdt(() -> view.leaveButton().isEnabled()));
        assertEquals(controller.getJournal(), onEdt(() -> view.journalArea().getText()), "refreshed after the load");
    }

    @Test
    void aWholeSessionThroughTheButtonsLeavesEveryPanelConsistent() throws Exception {
        open(20, 1, 20);
        askBrannForWork();
        click(view::acceptButton);
        click(view::leaveButton);
        walk("village-square", "forest-edge", "wolf-den");
        type("attack wolf");
        click(view::fleeButton);
        edt(() -> view.filterBox().setSelectedIndex(3));
        view.awaitIdle();

        String story = onEdt(() -> view.storyArea().getText());
        assertFalse(story.contains("That did not work"), story);
        assertEquals("none", onEdt(view::modePanelName));
        assertEquals(2, onEdt(() -> view.questList().getModel().getSize()));
        assertEquals("Combat", onEdt(() -> view.filterBox().getSelectedItem().toString()));
        assertEquals(controller.getHistory(EventType.COMBAT).size(), onEdt(() -> view.eventTable().getRowCount()));
        assertEquals("fled from the wolf", onEdt(() -> column(3).get(0)));
        assertEquals(controller.getJournal(), onEdt(() -> view.journalArea().getText()));
        assertTrue(onEdt(() -> view.inputField().isEnabled()));
    }
}
