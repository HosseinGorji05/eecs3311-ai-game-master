package presentation;

import agent.NpcReply;
import engine.character.CharacterClass;
import engine.character.Player;
import engine.character.Stats;
import engine.commands.ActionProposal;
import engine.core.Ending;
import engine.core.EndingType;
import engine.core.FixedDiceRoller;
import engine.events.EventType;
import engine.events.GameEvent;
import engine.persistence.SaveManager;
import engine.persistence.SaveSlotInfo;
import engine.quests.Quest;
import llm.ScriptedLLMClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Drives the real Swing view through the real controller (offline scripted client, fixed dice, saves in a
 * temporary folder), wrapped so every controller call is recorded. The window is never shown. Skipped,
 * not failed, without a display.
 */
@Timeout(60)
class SwingGameViewTest {

    /** The real controller, recording each call the view makes and the thread it runs on. */
    private static final class RecordingController extends GameController {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<Boolean> onEventThread = new CopyOnWriteArrayList<>();

        /** The dice are only rolled by the wolf fight: the player misses (1), the wolf hits (20). */
        RecordingController(Path saves) {
            super(new ScriptedLLMClient(List.of()), new FixedDiceRoller(1, 20), new SaveManager(saves));
        }

        private void record(String call) {
            calls.add(call);
            onEventThread.add(SwingUtilities.isEventDispatchThread());
        }

        @Override
        public Player newGame(String name, CharacterClass cls, Stats stats) {
            record("newGame " + name + " " + cls);
            return super.newGame(name, cls, stats);
        }

        @Override
        public void startGame() {
            record("startGame");
            super.startGame();
        }

        @Override
        public TurnResult submitCommand(String text) {
            record("submitCommand " + text);
            return super.submitCommand(text);
        }

        @Override
        public TurnResult performAction(ActionProposal proposal) {
            record("performAction " + proposal.getType() + (proposal.getTarget() == null ? "" : " " + proposal.getTarget()));
            return super.performAction(proposal);
        }

        @Override
        public NpcReply sendDialogue(String message) {
            record("sendDialogue " + message);
            return super.sendDialogue(message);
        }

        @Override
        public void respondToQuest(Quest quest, boolean accept) {
            record("respondToQuest " + accept);
            super.respondToQuest(quest, accept);
        }

        @Override
        public void saveGame(String slot) {
            record("saveGame " + slot);
            super.saveGame(slot);
        }

        @Override
        public void loadGame(String slot) {
            record("loadGame " + slot);
            super.loadGame(slot);
        }

        @Override
        public List<SaveSlotInfo> listSaves() {
            record("listSaves");
            return super.listSaves();
        }
    }

    @TempDir
    Path saves;

    private RecordingController controller;
    private SwingGameView view;

    @BeforeEach
    void createView() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        controller = new RecordingController(saves);
        view = onEdt(() -> new SwingGameView(controller));
        controller.addView(view);
    }

    @AfterEach
    void closeWindows() throws Exception {
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

    private void startRogue() throws Exception {
        edt(() -> {
            view.nameField().setText("Ari");
            view.classBox().setSelectedItem(CharacterClass.ROGUE);
            List<JSlider> sliders = view.statSliders();
            sliders.get(0).setValue(2);
            sliders.get(1).setValue(6);
            sliders.get(2).setValue(2);
            view.createButton().doClick();
        });
        edt(() -> view.startButton().doClick());   // F01/SD01: Generate Backstory, then Start
        controller.calls.clear();
        controller.onEventThread.clear();
    }

    private void type(String line) throws Exception {
        edt(() -> {
            view.inputField().setText(line);
            view.sendButton().doClick();
        });
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

    private void selectCarried(String... names) throws Exception {
        edt(() -> {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) view.inventoryTree().getModel().getRoot();
            for (String name : names) {
                DefaultMutableTreeNode next = null;
                for (int i = 0; i < node.getChildCount(); i++) {
                    DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
                    if (((InventoryTreeModel.ItemNode) child.getUserObject()).name().equals(name)) {
                        next = child;
                    }
                }
                node = next;
            }
            view.inventoryTree().setSelectionPath(new TreePath(node.getPath()));
        });
    }

    private void selectHere(String name) throws Exception {
        edt(() -> {
            JList<InventoryTreeModel.ItemNode> list = view.itemsHereList();
            for (int i = 0; i < list.getModel().getSize(); i++) {
                if (list.getModel().getElementAt(i).name().equals(name)) {
                    list.setSelectedIndex(i);
                }
            }
        });
    }

    private String story() throws Exception {
        return onEdt(() -> view.storyArea().getText());
    }

    @Test
    void theNewGameScreenValidatesLiveAndStartCreatesAndStartsTheGame() throws Exception {
        assertEquals(SwingGameView.NEW_GAME_CARD, onEdt(view::currentCard));
        edt(() -> view.nameField().setText(" "));
        assertFalse(onEdt(() -> view.createButton().isEnabled()));
        assertEquals(GameController.NAME_MISSING, onEdt(() -> view.newGameMessage().getText()));

        edt(() -> {
            view.nameField().setText("Ari");
            view.statSliders().get(0).setValue(8);
        });
        assertFalse(onEdt(() -> view.createButton().isEnabled()));
        assertEquals(GameController.STATS_INVALID, onEdt(() -> view.newGameMessage().getText()));
        assertEquals("3 points too many", onEdt(() -> view.pointsLabel().getText()));

        startRogue();

        assertEquals(SwingGameView.GAME_CARD, onEdt(view::currentCard));
        assertEquals("Ari the Rogue | HP 24/24 | Gold 0 | XP 0 | Village Square | Turn 1",
                onEdt(() -> view.statusLabel().getText()));
        assertTrue(story().contains("Village Square\nA muddy square"), story());
        assertEquals(1, onEdt(() -> view.questList().getModel().getSize()), "the main quest");
        assertTrue(onEdt(() -> view.questText(view.questList().getModel().getElementAt(0)))
                .contains("Recover the village relic"));
    }

    @Test
    void startCallsNewGameThenStartGameOffTheEventThread() throws Exception {
        edt(() -> {
            view.nameField().setText("  Ari ");
            view.classBox().setSelectedItem(CharacterClass.MAGE);
            view.statSliders().get(0).setValue(2);
            view.statSliders().get(1).setValue(3);
            view.statSliders().get(2).setValue(5);
            view.createButton().doClick();
        });
        edt(() -> view.startButton().doClick());   // F01/SD01: Generate Backstory, then Start

        assertEquals(List.of("newGame Ari MAGE", "startGame"), controller.calls);
        assertEquals(List.of(false, false), controller.onEventThread);
    }

    @Test
    void typedTextGoesToSubmitCommandAndTheFixedWordsToTheirActions() throws Exception {
        startRogue();

        type("look around");
        type("flee");
        type("accept");

        assertEquals(List.of("submitCommand look around", "performAction FLEE", "respondToQuest true"), controller.calls);
        assertTrue(story().contains("> look around"), story());
        assertTrue(story().contains("! " + GameController.NO_OFFER), story());
        assertTrue(controller.onEventThread.stream().noneMatch(Boolean::booleanValue));
    }

    @Test
    void aMapClickOnANeighbourMovesThereWithoutTheModel() throws Exception {
        startRogue();
        assertTrue(onEdt(() -> view.mapPanel().isMoveEnabled()));

        clickOnMap("tavern");

        assertEquals(List.of("performAction MOVE Tavern"), controller.calls);
        assertEquals("tavern", controller.getState().currentLocation().getId());
        assertEquals(MapModel.NodeState.CURRENT, onEdt(() -> view.mapPanel().getModel().find("tavern").state()));
    }

    @Test
    void aClickOnAPlaceThatIsNotNextToThePlayerDoesNothing() throws Exception {
        startRogue();
        clickOnMap("tavern");
        controller.calls.clear();

        clickOnMap("tool-shed");

        assertEquals(List.of(), controller.calls, "the Tool Shed is two steps away from the Tavern");
    }

    @Test
    void theInventoryButtonsSendTheSelectedItem() throws Exception {
        startRogue();
        assertFalse(onEdt(() -> view.useButton().isEnabled()), "nothing selected yet");

        selectCarried("pouch", "healing potion");
        edt(() -> view.useButton().doClick());
        selectCarried("dagger");
        edt(() -> view.equipButton().doClick());
        selectCarried("dagger");
        edt(() -> view.dropButton().doClick());

        assertEquals(List.of("performAction USE healing potion", "performAction EQUIP dagger", "performAction DROP dagger"),
                controller.calls);
        assertTrue(story().contains("> equip dagger"), story());
    }

    @Test
    void takeSendsTheSelectedItemLyingHereAndTheWeightBarFollows() throws Exception {
        startRogue();
        clickOnMap("tavern");
        controller.calls.clear();
        assertEquals("1.7 of 10 kg", onEdt(() -> view.weightBar().getString()));

        selectHere("shed key");
        edt(() -> view.takeButton().doClick());

        assertEquals(List.of("performAction TAKE shed key"), controller.calls);
        assertEquals("1.8 of 10 kg", onEdt(() -> view.weightBar().getString()));
        assertNotNull(controller.getState().getPlayer().getInventory().find("shed key"));
    }

    @Test
    void inAConversationTypedTextIsSaidToTheNpcAndOnlyTypingWorks() throws Exception {
        startRogue();
        clickOnMap("tavern");
        type("talk to Marta");
        controller.calls.clear();

        assertEquals("Say to Marta:", onEdt(() -> view.promptLabel().getText()));
        assertTrue(story().contains("You are talking to Marta."), story());
        selectHere("bread");
        assertFalse(onEdt(() -> view.takeButton().isEnabled()), "a TAKE here would count as Marta's handover");
        selectCarried("dagger");
        assertFalse(onEdt(() -> view.dropButton().isEnabled()));
        assertFalse(onEdt(() -> view.mapPanel().isMoveEnabled()));
        assertTrue(onEdt(() -> view.inputField().isEnabled()));

        type("Have you seen the bandits?");
        type("bye");

        assertEquals(List.of("sendDialogue Have you seen the bandits?", "performAction LEAVE"), controller.calls);
        assertTrue(story().contains("Marta: \""), story());
        assertEquals(GuiText.COMMAND_PROMPT, onEdt(() -> view.promptLabel().getText()));
        assertTrue(onEdt(() -> view.mapPanel().isMoveEnabled()));
    }

    @Test
    void saveAsksBeforeOverwritingAndLoadReplacesTheGame() throws Exception {
        startRogue();
        List<String> asked = new ArrayList<>();
        view.setOverwriteConfirmer(slot -> {
            asked.add(slot);
            return false;
        });

        assertTrue(onEdt(() -> view.confirmAndSave("slot1", List.of(), true)));
        view.awaitIdle();
        List<SaveSlotInfo> slots = controller.listSaves();
        assertFalse(onEdt(() -> view.confirmAndSave("SLOT1", slots, true)), "kept the old save");
        view.setOverwriteConfirmer(slot -> true);
        assertTrue(onEdt(() -> view.confirmAndSave("slot1", slots, true)));
        view.awaitIdle();

        assertEquals(List.of("SLOT1"), asked);
        assertEquals(List.of("saveGame slot1", "listSaves", "saveGame slot1"), controller.calls);
        assertTrue(story().contains("(" + GameController.SAVED + ")"), story());

        clickOnMap("tavern");
        controller.calls.clear();
        edt(() -> view.loadSlot("slot1"));

        assertEquals(List.of("loadGame slot1"), controller.calls);
        assertEquals("village-square", controller.getState().currentLocation().getId());
        assertTrue(onEdt(() -> view.statusLabel().getText()).contains("Village Square"));
    }

    @Test
    void theLoadMenuListsTheSavesAndItsLoadButtonLoadsTheChosenOne() throws Exception {
        startRogue();
        edt(() -> view.confirmAndSave("first", List.of(), true));
        controller.calls.clear();

        edt(() -> view.loadItem().doClick());

        assertEquals(List.of("listSaves"), controller.calls);
        assertTrue(onEdt(() -> view.saveDialog().isShowing()));
        @SuppressWarnings("unchecked")
        JList<SaveSlotInfo> list = onEdt(() -> (JList<SaveSlotInfo>) find(view.saveDialog().getContentPane(), JList.class));
        assertEquals("first", onEdt(() -> list.getModel().getElementAt(0).getSlot()));
        edt(() -> {
            list.setSelectedIndex(0);
            find(view.saveDialog().getContentPane(), JButton.class, "Load").doClick();
        });
        view.awaitIdle();

        assertEquals(List.of("listSaves", "loadGame first"), controller.calls);
        assertFalse(onEdt(() -> view.saveDialog().isShowing()));
    }

    @Test
    void renderAndEventsFromAnotherThreadUpdateTheComponentsOnTheEventThread() throws Exception {
        startRogue();
        List<Boolean> updates = new CopyOnWriteArrayList<>();
        edt(() -> {
            view.statusLabel().addPropertyChangeListener("text",
                    event -> updates.add(SwingUtilities.isEventDispatchThread()));
            view.questList().addPropertyChangeListener("model",
                    event -> updates.add(SwingUtilities.isEventDispatchThread()));
        });
        controller.getState().getPlayer().takeDamage(5);

        view.render(controller.getState());
        Thread other = new Thread(() -> view.onEvent(new GameEvent(EventType.QUEST, 1, "Quest test", "q", 0)));
        other.start();
        other.join();
        view.awaitIdle();

        assertTrue(updates.size() >= 3, updates.toString());
        assertTrue(updates.stream().allMatch(Boolean::booleanValue), updates.toString());
        assertTrue(onEdt(() -> view.statusLabel().getText()).contains("HP 19/24"));
    }

    @Test
    void showEndingShowsTheEndScreenOnTheEventThreadAndLeavesOnlyNewGameAndLoad() throws Exception {
        startRogue();
        List<Boolean> updates = new CopyOnWriteArrayList<>();
        edt(() -> view.endTitle().addPropertyChangeListener("text",
                event -> updates.add(SwingUtilities.isEventDispatchThread())));
        Ending ending = new Ending(EndingType.NEUTRAL, Map.of("turns played", 3));
        ending.setEpilogue("The village goes on.");

        view.showEnding(ending);
        view.awaitIdle();

        assertEquals(List.of(true), updates);
        assertEquals(SwingGameView.END_CARD, onEdt(view::currentCard));
        assertEquals("The End: Neutral ending", onEdt(() -> view.endTitle().getText()));
        assertEquals("The village goes on.", onEdt(() -> view.epilogueArea().getText()));
        assertEquals("Turns played: 3", onEdt(() -> view.endStatistics().getText()));
        assertFalse(onEdt(() -> view.inputField().isEnabled()));
        assertFalse(onEdt(() -> view.saveItem().isEnabled()));
        assertFalse(onEdt(() -> view.mapPanel().isMoveEnabled()));
        assertTrue(onEdt(() -> view.endNewGameButton().isEnabled()));
        assertTrue(onEdt(() -> view.endLoadButton().isEnabled()));

        edt(() -> view.endNewGameButton().doClick());
        assertEquals(SwingGameView.NEW_GAME_CARD, onEdt(view::currentCard));
        edt(() -> view.createButton().doClick());
        edt(() -> view.startButton().doClick());
        assertEquals(List.of("newGame Ari ROGUE", "startGame"), controller.calls);
        assertEquals(SwingGameView.GAME_CARD, onEdt(view::currentCard));
        assertTrue(onEdt(() -> view.inputField().isEnabled()));
    }

    @Test
    void dyingInATypedFightShowsTheEndScreen() throws Exception {
        startRogue();
        Player player = controller.getState().getPlayer();
        player.takeDamage(player.getHp() - 1);
        clickOnMap("forest-edge");
        clickOnMap("wolf-den");
        controller.calls.clear();

        type("attack wolf");

        assertEquals(List.of("submitCommand attack wolf"), controller.calls);
        assertTrue(controller.getState().isGameOver());
        assertEquals(SwingGameView.END_CARD, onEdt(view::currentCard));
        assertEquals("The End: Death ending", onEdt(() -> view.endTitle().getText()));
        assertFalse(onEdt(() -> view.epilogueArea().getText()).isBlank());
        assertFalse(onEdt(() -> view.inputField().isEnabled()));
        assertTrue(onEdt(() -> view.endLoadButton().isEnabled()));
    }

    private static <T> T find(Container root, Class<T> type) {
        return find(root, type, null);
    }

    /** The first component of the type inside {@code root}; for a button, the one with this text. */
    private static <T> T find(Container root, Class<T> type, String buttonText) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component) && (buttonText == null
                    || buttonText.equals(((AbstractButton) component).getText()))) {
                return type.cast(component);
            }
            if (component instanceof Container inner) {
                T found = find(inner, type, buttonText);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
