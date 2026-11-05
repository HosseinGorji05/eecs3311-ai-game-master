package presentation;

import agent.NpcReply;
import engine.character.CharacterClass;
import engine.commands.ActionProposal;
import engine.commands.ActionType;
import engine.core.DialogueMode;
import engine.core.Ending;
import engine.core.GameState;
import engine.events.EventType;
import engine.events.GameEvent;
import engine.events.GameEventListener;
import engine.persistence.SaveSlotInfo;
import engine.quests.Quest;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.WindowConstants;
import javax.swing.border.Border;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.reflect.InvocationTargetException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The Swing desktop interface: the New Game screen, the main window with the story panel and its input
 * field, the status area, the map with the people here, the inventory, the quest log, the event log and
 * the journal, the dialogue panel with the quest offer card and the combat panel, the Game menu with Save,
 * Load and New Game, and the end screen. It forwards all input to the single {@link GameController} and
 * never touches the engine.
 *
 * <p>Pattern roles: <b>View</b> of MVC and <b>ConcreteObserver</b> of the Observer pattern: every game
 * event makes it refresh its panels (SD11), and quest updates are shown in the story panel after the
 * turn's narration, as in the CLI.
 *
 * <p><b>Threads.</b> Every controller call runs on one worker thread, because a turn can wait for the
 * model; while it runs, the input is disabled. {@link #render}, {@link #onEvent} and the other
 * {@link GameView} methods may be called on any thread: they copy what they show from the game state
 * on the calling thread (the one running the controller, see {@link GuiState}) and update the Swing
 * components on the event thread. The Swing thread never reads the live game state.
 *
 * <p><b>Input.</b> The input field sends a line the way the CLI does ({@link TypedInput}): to
 * {@code submitCommand} (F02), in a conversation to {@code sendDialogue}, and the fixed words leave, flee,
 * accept and decline to {@code performAction} and {@code respondToQuest}. A click on a location next to
 * the current one (F06, SD11) and the Take, Use, Equip and Drop buttons (F05, SD10) send a proposal
 * through {@code performAction}, without the model. {@link ControlRules} turns the controls off where
 * the CLI could not do the same.
 *
 * <p><b>Mode panels.</b> Above the story panel, the dialogue panel (F03, SD07) is shown while the mode is a
 * conversation: the NPC's name, a drawn portrait, the conversation's lines ({@link ConversationLog}), a
 * message field whose lines go where the story input's do, and Leave (a LEAVE proposal, SD12). An offer
 * ({@link #showQuestOffer}) appears in it as a card with Accept and Decline ({@code respondToQuest}, F07).
 * In combat the combat panel (F04, SD04) shows both fighters' HP bars, the last round's dice from the
 * engine's COMBAT events ({@link CombatSummary}) and Attack and Flee ({@code performAction}). A click on a
 * person here sends a TALK proposal (F03). The story panel keeps the whole text in every mode.
 *
 * <p><b>Event log and journal.</b> The Event log tab (F11, UC12, SD06) is a table of the history, newest
 * first, from {@code getRecentHistory} or, for a type filter, {@code getHistory(type)}; it refreshes with
 * every event. The Journal tab (F09) shows {@code getJournal()}, refreshed after every controller call and
 * after a load; it never calls the model.
 */
public class SwingGameView implements GameView, GameEventListener {

    static final String TITLE = "AI Game Master";
    static final String NEW_GAME_CARD = "new-game";
    static final String GAME_CARD = "game";
    static final String END_CARD = "end";
    private static final int STORY_FONT_SIZE = 14;
    private static final int WEIGHT_SCALE = 10;
    private static final Color PROBLEM = new Color(170, 30, 30);

    private final GameController controller;
    private final JFrame frame;
    private final JPanel newGamePanel;
    private final JTextArea storyArea;
    private final JTextField inputField;
    private final MapPanel mapPanel;
    private final JTree inventoryTree;
    private final JList<Quest> questList;
    private JDialog saveDialog;
    private final JPanel endPanel;
    private final JPanel dialoguePanel = new JPanel(new BorderLayout(10, 6));
    private final JPanel combatPanel = new JPanel(new BorderLayout(10, 6));
    private final JTable eventTable = new JTable(EventTableModel.empty());
    private final JTextArea journalArea = new JTextArea();

    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "game-controller");
        thread.setDaemon(true);
        return thread;
    });
    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel(cards);
    private String currentCard = NEW_GAME_CARD;

    private final JLabel statusLabel = new JLabel(GuiText.NO_GAME);
    private final JLabel modeLabel = new JLabel(" ");
    private final JLabel promptLabel = new JLabel(GuiText.COMMAND_PROMPT);
    private final JButton sendButton = new JButton("Send");
    private final JProgressBar weightBar = new JProgressBar();
    private final JList<InventoryTreeModel.ItemNode> itemsHereList = new JList<>();
    private final JButton takeButton = new JButton("Take");
    private final JButton useButton = new JButton("Use");
    private final JButton equipButton = new JButton("Equip");
    private final JButton dropButton = new JButton("Drop");
    private final JMenuItem newGameItem = new JMenuItem("New Game");
    private final JMenuItem saveItem = new JMenuItem("Save...");
    private final JMenuItem loadItem = new JMenuItem("Load...");

    private final JTextField nameField = new JTextField(18);
    private final JComboBox<CharacterClass> classBox = new JComboBox<>(CharacterClass.values());
    private final JSlider strengthSlider = statSlider();
    private final JSlider agilitySlider = statSlider();
    private final JSlider intelligenceSlider = statSlider();
    private final JLabel pointsLabel = new JLabel();
    private final JLabel newGameMessage = new JLabel(" ");
    private final JButton startButton = new JButton("Start");
    private final JButton newGameLoadButton = new JButton("Load Game...");
    private Border nameBorder;

    private final JLabel endTitle = new JLabel(" ");
    private final JTextArea epilogueArea = new JTextArea();
    private final JLabel endStatistics = new JLabel(" ");
    private final JLabel endMessage = new JLabel(" ");
    private final JButton endNewGameButton = new JButton("New Game");
    private final JButton endLoadButton = new JButton("Load Game...");

    private final JPanel modeHolder = new JPanel(new BorderLayout());
    private JComponent shownModePanel;
    private final JPanel peoplePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
    private final List<JButton> talkButtons = new ArrayList<>();
    private List<DialogueState.Person> shownPeople = List.of();

    private final Portrait portrait = new Portrait();
    private final JLabel dialogueHeading = new JLabel(" ");
    private final JTextArea conversationArea = new JTextArea(6, 30);
    private final JTextField dialogueField = new JTextField();
    private final JButton dialogueSendButton = new JButton("Send");
    private final JButton leaveButton = new JButton("Leave");
    private final JPanel questCardPanel = new JPanel(new BorderLayout(6, 6));
    private final JLabel questCardText = new JLabel();
    private final JButton acceptButton = new JButton("Accept");
    private final JButton declineButton = new JButton("Decline");
    private final ConversationLog conversation = new ConversationLog();

    private final JLabel playerHpLabel = new JLabel(" ");
    private final JLabel enemyHpLabel = new JLabel(" ");
    private final JProgressBar playerHpBar = new JProgressBar();
    private final JProgressBar enemyHpBar = new JProgressBar();
    private final JTextArea diceArea = new JTextArea(3, 30);
    private final JButton attackButton = new JButton("Attack");
    private final JButton fleeButton = new JButton("Flee");

    private final JComboBox<HistoryFilter> filterBox = new JComboBox<>(HistoryFilter.choices().toArray(new HistoryFilter[0]));
    private final JCheckBox showAllBox = new JCheckBox("Show all");
    private final JLabel eventCountLabel = new JLabel(" ");
    private volatile HistoryFilter historyFilter = HistoryFilter.ALL;
    private volatile boolean showAllEvents;

    private GuiState shown = GuiState.empty();
    /** Controller calls submitted and not finished yet; the input is off while there are any. */
    private int pendingCalls;
    private boolean busy;
    private boolean endingShown;
    /** The quest on the card, which a typed accept or decline also answers; on the event thread. */
    private Quest offeredQuest;
    private final List<String> pendingNotices = Collections.synchronizedList(new ArrayList<>());
    private Predicate<String> overwriteConfirmer = this::askOverwrite;

    /**
     * Builds the window (not shown yet) on the Swing event thread; {@link #launch} also adds it to the
     * controller and shows it.
     *
     * @throws IllegalStateException if not called on the event thread
     */
    public SwingGameView(GameController controller) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Create the Swing view on the event dispatch thread");
        }
        this.controller = Objects.requireNonNull(controller, "controller");
        this.storyArea = new JTextArea();
        this.inputField = new JTextField();
        this.mapPanel = new MapPanel();
        this.inventoryTree = new JTree(InventoryTreeModel.empty());
        this.questList = new JList<>();
        this.newGamePanel = buildNewGamePanel();
        this.endPanel = buildEndPanel();
        cardPanel.add(newGamePanel, NEW_GAME_CARD);
        cardPanel.add(buildGamePanel(), GAME_CARD);
        cardPanel.add(endPanel, END_CARD);
        this.frame = new JFrame(TITLE);
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.setJMenuBar(buildMenuBar());
        frame.setContentPane(cardPanel);
        frame.setMinimumSize(new Dimension(820, 560));
        frame.setSize(new Dimension(1120, 740));
        frame.setLocationByPlatform(true);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                worker.shutdownNow();
            }
        });
        showCard(NEW_GAME_CARD);
        updateAllocation();
        updateControls();
    }

    /**
     * Creates the view on the event thread with the system look and feel, adds it to the controller (it
     * also listens to the game's events) and shows the window on the New Game screen.
     */
    public static SwingGameView launch(GameController controller) {
        Objects.requireNonNull(controller, "controller");
        SwingGameView view = onEdtAndWait(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (ReflectiveOperationException | UnsupportedLookAndFeelException ignored) {
                // the default look and feel is fine
            }
            return new SwingGameView(controller);
        });
        controller.addView(view);
        SwingUtilities.invokeLater(view::show);
        return view;
    }

    /** Shows the window; on the event thread. */
    void show() {
        frame.setVisible(true);
        nameField.requestFocusInWindow();
    }

    /**
     * Shows the loaded or new game: the location in the story panel and every panel refreshed, the event
     * log and the journal included. A conversation in a loaded game opens the dialogue panel afresh.
     */
    @Override
    public void render(GameState state) {
        GuiState copy = GuiState.of(state);
        String arrival = state.currentLocation() == null ? null : state.currentLocation().describe();
        String mode = GuiText.modeLine(state);
        EventTableModel history = history();
        String journal = journal(state);
        onEdt(() -> {
            endingShown = false;
            hideQuestCard();
            if (storyArea.getDocument().getLength() == 0) {
                append(GuiText.HELP);
            }
            if (arrival != null) {
                append("\n" + arrival);
            }
            if (!mode.isEmpty()) {
                append(mode);
            }
            apply(copy, true);
            showHistory(history);
            showJournal(journal);
            if (copy.getRules().isStarted()) {
                showCard(GAME_CARD);
                inputField.requestFocusInWindow();
            }
        });
    }

    @Override
    public void showNarration(String text) {
        if (text != null && !text.isEmpty()) {
            onEdt(() -> append(text));
        }
    }

    @Override
    public void showNotice(String text) {
        onEdt(() -> append("(" + text + ")"));
    }

    /** In the story panel; on the New Game and end screens also on the screen itself. */
    @Override
    public void showError(String message) {
        onEdt(() -> {
            append("! " + message);
            if (currentCard.equals(NEW_GAME_CARD)) {
                showNewGameMessage(message);
            } else if (currentCard.equals(END_CARD)) {
                endMessage.setText(message);
            }
        });
    }

    /**
     * Shows the offer as a card in the dialogue panel with Accept and Decline (F07, SD07), and its text in
     * the story panel as the CLI prints it; a typed accept or decline answers it too.
     */
    @Override
    public void showQuestOffer(Quest quest) {
        String text = GuiText.questOffer(quest, controller.getState().getWorld());
        DialogueState.QuestCard card = DialogueState.QuestCard.of(quest, controller.getState().getWorld());
        onEdt(() -> {
            append(text);
            offeredQuest = quest;
            questCardPanel.setBorder(BorderFactory.createTitledBorder(card.giver() + " offers you a quest"));
            questCardText.setText(card.toHtml());
            questCardPanel.setVisible(true);
            dialoguePanel.revalidate();
            updateControls();
        });
    }

    /**
     * Shows the end screen (F12, SD09): the ending type, the epilogue and the statistics, with New Game
     * and Load Game. The quest updates of the last turn are shown first. Until a new game starts or a save
     * is loaded, every other input is off.
     */
    @Override
    public void showEnding(Ending ending) {
        flushNotices();
        String title = GuiText.endingTitle(ending.getType());
        String epilogue = ending.getEpilogue() == null ? "" : ending.getEpilogue();
        String statistics = GuiText.statistics(ending);
        onEdt(() -> {
            endingShown = true;
            hideQuestCard();
            endTitle.setText(title);
            epilogueArea.setText(epilogue);
            epilogueArea.setCaretPosition(0);
            endStatistics.setText(statistics);
            endMessage.setText(" ");
            showCard(END_CARD);
            updateControls();
        });
    }

    /** Remembers quest updates for after the turn's narration, then refreshes the panels (SD11). */
    @Override
    public void onEvent(GameEvent event) {
        if (event.getType() == EventType.QUEST) {
            pendingNotices.add(event.getDescription());
        }
        refresh();
    }

    /** Copies the controller's state and the event log on this thread and shows them on the event thread. */
    private void refresh() {
        GuiState copy = GuiState.of(controller.getState());
        EventTableModel history = history();
        onEdt(() -> {
            apply(copy, false);
            showHistory(history);
        });
    }

    /** The event log panel's rows for the chosen filter (SD06), read through the controller on this thread. */
    private EventTableModel history() {
        HistoryFilter filter = historyFilter;
        int cap = showAllEvents ? Integer.MAX_VALUE : EventTableModel.DEFAULT_CAP;
        List<GameEvent> events = filter.isAll()
                ? controller.getRecentHistory(cap == Integer.MAX_VALUE ? cap : cap + 1)
                : controller.getHistory(filter.type());
        return EventTableModel.of(events, cap, ZoneId.systemDefault());
    }

    /** The journal (SD06) read through the controller on this thread; empty before a game. */
    private String journal(GameState state) {
        return state.getPlayer() == null ? "" : controller.getJournal();
    }

    // ---- building the screens -------------------------------------------------------------------------

    private JMenuBar buildMenuBar() {
        JMenu game = new JMenu("Game");
        newGameItem.addActionListener(event -> openNewGame());
        saveItem.addActionListener(event -> chooseSlot(true));
        loadItem.addActionListener(event -> chooseSlot(false));
        JMenuItem quit = new JMenuItem("Quit");
        quit.addActionListener(event -> frame.dispose());
        game.add(newGameItem);
        game.add(saveItem);
        game.add(loadItem);
        game.addSeparator();
        game.add(quit);
        JMenuBar bar = new JMenuBar();
        bar.add(game);
        return bar;
    }

    private JPanel buildNewGamePanel() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
        GridBagConstraints at = new GridBagConstraints();
        at.insets = new Insets(6, 6, 6, 6);
        at.anchor = GridBagConstraints.WEST;
        JLabel heading = new JLabel("Create your character");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 20f));
        at.gridx = 0;
        at.gridy = 0;
        at.gridwidth = 2;
        form.add(heading, at);
        at.gridwidth = 1;
        addRow(form, at, 1, "Name:", nameField);
        addRow(form, at, 2, "Class:", classBox);
        addRow(form, at, 3, "Strength:", strengthSlider);
        addRow(form, at, 4, "Agility:", agilitySlider);
        addRow(form, at, 5, "Intelligence:", intelligenceSlider);
        at.gridx = 1;
        at.gridy = 6;
        form.add(pointsLabel, at);
        at.gridy = 7;
        newGameMessage.setForeground(PROBLEM);
        form.add(newGameMessage, at);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        buttons.add(startButton);
        buttons.add(Box.createHorizontalStrut(10));
        buttons.add(newGameLoadButton);
        at.gridy = 8;
        form.add(buttons, at);

        StatAllocation preset = StatAllocation.preset("", CharacterClass.WARRIOR);
        strengthSlider.setValue(preset.strength());
        agilitySlider.setValue(preset.agility());
        intelligenceSlider.setValue(preset.intelligence());
        nameBorder = nameField.getBorder();
        nameField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                updateAllocation();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                updateAllocation();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                updateAllocation();
            }
        });
        strengthSlider.addChangeListener(event -> updateAllocation());
        agilitySlider.addChangeListener(event -> updateAllocation());
        intelligenceSlider.addChangeListener(event -> updateAllocation());
        classBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
                                                          boolean focused) {
                String text = value == null ? "" : value.toString().charAt(0) + value.toString().substring(1).toLowerCase(Locale.ROOT);
                return super.getListCellRendererComponent(list, text, index, selected, focused);
            }
        });
        startButton.addActionListener(event -> start());
        nameField.addActionListener(event -> {
            if (startButton.isEnabled()) {
                start();
            }
        });
        newGameLoadButton.addActionListener(event -> chooseSlot(false));

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(form, BorderLayout.NORTH);
        return panel;
    }

    private JPanel buildGamePanel() {
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 14f));
        JPanel status = new JPanel();
        status.setLayout(new BoxLayout(status, BoxLayout.Y_AXIS));
        status.setBorder(BorderFactory.createEmptyBorder(8, 10, 6, 10));
        status.add(statusLabel);
        status.add(modeLabel);

        storyArea.setEditable(false);
        storyArea.setLineWrap(true);
        storyArea.setWrapStyleWord(true);
        storyArea.setMargin(new Insets(8, 8, 8, 8));
        storyArea.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, STORY_FONT_SIZE));
        inputField.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, STORY_FONT_SIZE));
        inputField.addActionListener(event -> send());
        sendButton.addActionListener(event -> send());
        JPanel input = new JPanel(new BorderLayout(6, 0));
        input.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        input.add(promptLabel, BorderLayout.WEST);
        input.add(inputField, BorderLayout.CENTER);
        input.add(sendButton, BorderLayout.EAST);
        JPanel story = new JPanel(new BorderLayout(0, 8));
        story.setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 6));
        modeHolder.setVisible(false);
        story.add(modeHolder, BorderLayout.NORTH);
        story.add(new JScrollPane(storyArea), BorderLayout.CENTER);
        story.add(input, BorderLayout.SOUTH);
        buildDialoguePanel();
        buildCombatPanel();

        mapPanel.setOnMove(node -> act(new ActionProposal(ActionType.MOVE, node.name(), null), "go to " + node.name()));
        JPanel map = titled("Map", new BorderLayout());
        map.add(mapPanel, BorderLayout.CENTER);
        JLabel legend = new JLabel("Click a place with a blue border to walk there. Dashed line: locked door.");
        legend.setFont(legend.getFont().deriveFont(11f));
        JPanel mapFoot = new JPanel(new BorderLayout());
        mapFoot.add(legend, BorderLayout.NORTH);
        mapFoot.add(peoplePanel, BorderLayout.CENTER);
        map.add(mapFoot, BorderLayout.SOUTH);
        showPeople(List.of());

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Inventory", buildInventoryTab());
        tabs.addTab("Quests", buildQuestTab());
        tabs.addTab("Event log", buildEventLogTab());
        tabs.addTab("Journal", buildJournalTab());

        JSplitPane side = new JSplitPane(JSplitPane.VERTICAL_SPLIT, map, tabs);
        side.setResizeWeight(0.45);
        side.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 10));
        JSplitPane main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, story, side);
        main.setResizeWeight(0.55);
        main.setBorder(null);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(status, BorderLayout.NORTH);
        panel.add(main, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildInventoryTab() {
        weightBar.setStringPainted(true);
        weightBar.setString(GuiText.weightText(0, shown.getMaxWeight()));
        inventoryTree.setRootVisible(true);
        inventoryTree.setShowsRootHandles(true);
        inventoryTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        inventoryTree.addTreeSelectionListener(event -> updateControls());
        useButton.addActionListener(event -> actOnCarried(ActionType.USE));
        equipButton.addActionListener(event -> actOnCarried(ActionType.EQUIP));
        dropButton.addActionListener(event -> actOnCarried(ActionType.DROP));
        JPanel carriedButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        carriedButtons.add(useButton);
        carriedButtons.add(equipButton);
        carriedButtons.add(dropButton);
        JPanel carried = titled("Carried", new BorderLayout());
        carried.add(weightBar, BorderLayout.NORTH);
        carried.add(new JScrollPane(inventoryTree), BorderLayout.CENTER);
        carried.add(carriedButtons, BorderLayout.SOUTH);

        itemsHereList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        itemsHereList.addListSelectionListener(event -> updateControls());
        itemsHereList.setVisibleRowCount(3);
        takeButton.addActionListener(event -> {
            InventoryTreeModel.ItemNode item = itemsHereList.getSelectedValue();
            if (item != null) {
                act(new ActionProposal(ActionType.TAKE, item.name(), null), "take " + item.name());
            }
        });
        JPanel hereButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        hereButtons.add(takeButton);
        JPanel here = titled("Items here", new BorderLayout());
        here.add(new JScrollPane(itemsHereList), BorderLayout.CENTER);
        here.add(hereButtons, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, carried, here);
        split.setResizeWeight(0.65);
        split.setBorder(null);
        JPanel tab = new JPanel(new BorderLayout());
        tab.add(split, BorderLayout.CENTER);
        return tab;
    }

    private JComponent buildQuestTab() {
        questList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        questList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
                                                          boolean focused) {
                QuestListModel.Entry entry = value instanceof Quest quest ? shown.getQuests().entryOf(quest) : null;
                String text = entry == null ? "" : entry.toHtml();
                JLabel label = (JLabel) super.getListCellRendererComponent(list, text, index, selected, focused);
                label.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
                return label;
            }
        });
        return new JScrollPane(questList);
    }

    /** F03: the NPC's name and portrait, the conversation, the message field with Send, Leave and the quest card. */
    private void buildDialoguePanel() {
        dialogueHeading.setFont(dialogueHeading.getFont().deriveFont(Font.BOLD, 15f));
        conversationArea.setEditable(false);
        conversationArea.setLineWrap(true);
        conversationArea.setWrapStyleWord(true);
        conversationArea.setMargin(new Insets(4, 6, 4, 6));
        conversationArea.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, STORY_FONT_SIZE - 1));
        dialogueField.addActionListener(event -> sendFromDialogue());
        dialogueSendButton.addActionListener(event -> sendFromDialogue());
        leaveButton.addActionListener(event -> leave());
        acceptButton.addActionListener(event -> answer(true));
        declineButton.addActionListener(event -> answer(false));

        JPanel say = new JPanel(new BorderLayout(6, 0));
        say.add(new JLabel("Say:"), BorderLayout.WEST);
        say.add(dialogueField, BorderLayout.CENTER);
        JPanel sayButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        sayButtons.add(dialogueSendButton);
        sayButtons.add(leaveButton);
        say.add(sayButtons, BorderLayout.EAST);

        JPanel cardButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        cardButtons.add(declineButton);
        cardButtons.add(acceptButton);
        questCardPanel.add(questCardText, BorderLayout.CENTER);
        questCardPanel.add(cardButtons, BorderLayout.SOUTH);
        questCardPanel.setVisible(false);

        JPanel center = new JPanel(new BorderLayout(0, 6));
        center.add(dialogueHeading, BorderLayout.NORTH);
        JScrollPane lines = new JScrollPane(conversationArea);
        lines.setPreferredSize(new Dimension(300, 120));
        center.add(lines, BorderLayout.CENTER);
        center.add(say, BorderLayout.SOUTH);

        dialoguePanel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder("Conversation"),
                BorderFactory.createEmptyBorder(2, 4, 4, 4)));
        dialoguePanel.add(portrait, BorderLayout.WEST);
        dialoguePanel.add(center, BorderLayout.CENTER);
        dialoguePanel.add(questCardPanel, BorderLayout.SOUTH);
    }

    /** F04: both fighters' HP bars, the last round's dice, Attack and Flee. */
    private void buildCombatPanel() {
        for (JProgressBar bar : List.of(playerHpBar, enemyHpBar)) {
            bar.setPreferredSize(new Dimension(200, 16));
        }
        playerHpBar.setForeground(new Color(60, 150, 70));
        enemyHpBar.setForeground(new Color(190, 60, 50));
        diceArea.setEditable(false);
        diceArea.setLineWrap(true);
        diceArea.setWrapStyleWord(true);
        diceArea.setOpaque(false);
        diceArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        attackButton.addActionListener(event -> attack());
        fleeButton.addActionListener(event -> act(new ActionProposal(ActionType.FLEE, null, null), "flee"));

        JPanel bars = new JPanel(new GridBagLayout());
        GridBagConstraints at = new GridBagConstraints();
        at.insets = new Insets(2, 0, 2, 8);
        at.anchor = GridBagConstraints.WEST;
        at.gridx = 0;
        at.gridy = 0;
        bars.add(playerHpLabel, at);
        at.gridx = 1;
        at.fill = GridBagConstraints.HORIZONTAL;
        at.weightx = 1;
        bars.add(playerHpBar, at);
        at.gridx = 0;
        at.gridy = 1;
        at.fill = GridBagConstraints.NONE;
        at.weightx = 0;
        bars.add(enemyHpLabel, at);
        at.gridx = 1;
        at.fill = GridBagConstraints.HORIZONTAL;
        at.weightx = 1;
        bars.add(enemyHpBar, at);

        JPanel dice = new JPanel(new BorderLayout());
        dice.setBorder(BorderFactory.createTitledBorder("Last round"));
        dice.add(diceArea, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttons.add(attackButton);
        buttons.add(fleeButton);

        combatPanel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder("Combat"),
                BorderFactory.createEmptyBorder(2, 4, 4, 4)));
        combatPanel.add(bars, BorderLayout.NORTH);
        combatPanel.add(dice, BorderLayout.CENTER);
        combatPanel.add(buttons, BorderLayout.SOUTH);
    }

    /** F11, UC12: the history table, newest first, with the type filter and "Show all". */
    private JPanel buildEventLogTab() {
        eventTable.setFillsViewportHeight(true);
        eventTable.getTableHeader().setReorderingAllowed(false);
        eventTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sizeEventColumns();
        filterBox.addActionListener(event -> {
            historyFilter = (HistoryFilter) filterBox.getSelectedItem();
            runOnWorker(() -> { });
        });
        showAllBox.setToolTipText("Also show events older than the " + EventTableModel.DEFAULT_CAP + " most recent");
        showAllBox.addActionListener(event -> {
            showAllEvents = showAllBox.isSelected();
            runOnWorker(() -> { });
        });
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        top.add(new JLabel("Show:"));
        top.add(filterBox);
        top.add(showAllBox);
        top.add(eventCountLabel);
        JPanel tab = new JPanel(new BorderLayout());
        tab.add(top, BorderLayout.NORTH);
        tab.add(new JScrollPane(eventTable), BorderLayout.CENTER);
        return tab;
    }

    /** F09: the journal, read-only. */
    private JComponent buildJournalTab() {
        journalArea.setEditable(false);
        journalArea.setLineWrap(true);
        journalArea.setWrapStyleWord(true);
        journalArea.setMargin(new Insets(8, 8, 8, 8));
        journalArea.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, STORY_FONT_SIZE - 1));
        return new JScrollPane(journalArea);
    }

    private JPanel buildEndPanel() {
        endTitle.setFont(endTitle.getFont().deriveFont(Font.BOLD, 22f));
        epilogueArea.setEditable(false);
        epilogueArea.setLineWrap(true);
        epilogueArea.setWrapStyleWord(true);
        epilogueArea.setMargin(new Insets(10, 10, 10, 10));
        epilogueArea.setFont(new Font(Font.SERIF, Font.PLAIN, 16));
        endMessage.setForeground(PROBLEM);
        endNewGameButton.addActionListener(event -> openNewGame());
        endLoadButton.addActionListener(event -> chooseSlot(false));
        JPanel top = new JPanel(new BorderLayout());
        top.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
        top.add(endTitle, BorderLayout.CENTER);
        JPanel bottom = new JPanel();
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        bottom.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        buttons.add(endNewGameButton);
        buttons.add(Box.createHorizontalStrut(10));
        buttons.add(endLoadButton);
        for (JComponent part : List.of(endStatistics, endMessage, buttons)) {
            part.setAlignmentX(Component.LEFT_ALIGNMENT);
            bottom.add(part);
            bottom.add(Box.createVerticalStrut(8));
        }
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(24, 30, 24, 30));
        panel.add(top, BorderLayout.NORTH);
        panel.add(new JScrollPane(epilogueArea), BorderLayout.CENTER);
        panel.add(bottom, BorderLayout.SOUTH);
        return panel;
    }

    // ---- actions (on the event thread) ----------------------------------------------------------------

    /** Creates the character and starts the game (SD01); the controller shows a problem with showError. */
    private void start() {
        StatAllocation allocation = allocation();
        String name = allocation.name() == null ? "" : allocation.name().trim();
        runOnWorker(() -> {
            if (controller.newGame(name, allocation.characterClass(), allocation.toStats()) != null) {
                controller.startGame();
            }
        });
    }

    /** The story panel's input. */
    private void send() {
        submitLine(inputField);
    }

    /** The dialogue panel's message field: the same routes as the story input, in a conversation. */
    private void sendFromDialogue() {
        submitLine(dialogueField);
    }

    /** Sends the typed line where the CLI would ({@link TypedInput}). */
    private void submitLine(JTextField field) {
        String line = field.getText().trim();
        TypedInput.Route route = TypedInput.route(line, shown.getRules().isTalking());
        if (route == TypedInput.Route.EMPTY || !field.isEnabled()) {
            return;
        }
        field.setText("");
        append("> " + line);
        switch (route) {
            case HELP -> append(GuiText.HELP);
            case LEAVE -> runOnWorker(() -> controller.performAction(new ActionProposal(ActionType.LEAVE, null, null)));
            case FLEE -> runOnWorker(() -> controller.performAction(new ActionProposal(ActionType.FLEE, null, null)));
            case ACCEPT, DECLINE -> respond(route == TypedInput.Route.ACCEPT);
            case SAY -> say(line);
            case COMMAND -> runOnWorker(() -> controller.submitCommand(line));
            case EMPTY -> {
            }
        }
    }

    /** Says the line to the NPC (SD07); the line and the reply's text join the conversation. */
    private void say(String line) {
        String npcName = shown.getDialogue().npcName();
        conversation.addPlayerLine(line);
        showConversation();
        runOnWorker(() -> {
            NpcReply reply = controller.sendDialogue(line);
            if (reply != null) {
                onEdt(() -> {
                    if (conversation.isActive()) {
                        conversation.addNpcLine(npcName, reply.getText());
                        showConversation();
                    }
                });
            }
        });
    }

    /** The dialogue panel's Leave: a LEAVE proposal, as typing leave (SD12), so it never reaches the NPC. */
    private void leave() {
        act(new ActionProposal(ActionType.LEAVE, null, null), "leave");
    }

    /** The quest card's Accept or Decline. */
    private void answer(boolean accept) {
        append("> " + (accept ? "accept" : "decline"));
        respond(accept);
    }

    /** Answers the offer on the card (or none, which the controller refuses); the card goes away. */
    private void respond(boolean accept) {
        Quest quest = offeredQuest;
        hideQuestCard();
        runOnWorker(() -> controller.respondToQuest(quest, accept));
    }

    /** The combat panel's Attack: the enemy being fought (F04). */
    private void attack() {
        CombatSummary.Fighter enemy = shown.getCombat().getEnemy();
        if (enemy != null) {
            act(new ActionProposal(ActionType.ATTACK, enemy.name(), null), "attack " + enemy.name());
        }
    }

    /** A map click or an inventory button: the proposal goes straight to performAction, without the model. */
    private void act(ActionProposal proposal, String echo) {
        if (busy) {
            return;
        }
        append("> " + echo);
        runOnWorker(() -> controller.performAction(proposal));
    }

    private void actOnCarried(ActionType type) {
        InventoryTreeModel.ItemNode item = selectedCarried();
        if (item != null) {
            act(new ActionProposal(type, item.name(), null), type.name().toLowerCase(Locale.ROOT) + " " + item.name());
        }
    }

    private void openNewGame() {
        if (!shown.getRules().enabled(ControlRules.Control.NEW_GAME, busy, endingShown)) {
            return;
        }
        newGameMessage.setText(" ");
        showCard(NEW_GAME_CARD);
        updateAllocation();
        nameField.requestFocusInWindow();
    }

    /**
     * Opens the save or load dialog with the saved games (SD08): the list is read through the controller,
     * together with whether saving is allowed now, which decides whether the view asks before overwriting.
     */
    private void chooseSlot(boolean saving) {
        runOnWorker(() -> {
            List<SaveSlotInfo> slots = controller.listSaves();
            GameState state = controller.getState();
            boolean askOverwrite = saving && state.getMode() != null && state.canSave() && !state.isGameOver();
            onEdt(() -> openSlotDialog(saving, slots, askOverwrite));
        });
    }

    /**
     * Saves in the slot, first asking whether to overwrite a listed save of that name when saving is allowed
     * (as the CLI does; SD08). The controller then saves or shows why not.
     *
     * @return {@code false} if the player kept the existing save
     */
    boolean confirmAndSave(String slot, List<SaveSlotInfo> slots, boolean askOverwrite) {
        boolean exists = slots.stream().anyMatch(info -> info.getSlot().equalsIgnoreCase(slot));
        if (exists && askOverwrite && !overwriteConfirmer.test(slot)) {
            return false;
        }
        runOnWorker(() -> controller.saveGame(slot));
        return true;
    }

    /** Replaces the game with the save (SD08); the controller renders it or shows an error. */
    void loadSlot(String slot) {
        offeredQuest = null;
        runOnWorker(() -> controller.loadGame(slot));
    }

    private void openSlotDialog(boolean saving, List<SaveSlotInfo> slots, boolean askOverwrite) {
        JDialog dialog = new JDialog(frame, saving ? "Save game" : "Load game", true);
        DefaultListModel<SaveSlotInfo> model = new DefaultListModel<>();
        slots.forEach(model::addElement);
        JList<SaveSlotInfo> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(8);
        ZoneId zone = ZoneId.systemDefault();
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> owner, Object value, int index, boolean selected,
                                                          boolean focused) {
                String text = value instanceof SaveSlotInfo info ? GuiText.slotLabel(info, zone) : "";
                return super.getListCellRendererComponent(owner, text, index, selected, focused);
            }
        });
        JTextField slotField = new JTextField(20);
        JLabel message = new JLabel(slots.isEmpty() ? "No saves yet." : " ");
        JButton confirm = new JButton(saving ? "Save" : "Load");
        JButton cancel = new JButton("Cancel");
        Runnable choose = () -> {
            if (saving) {
                String slot = slotField.getText().trim();
                if (slot.isEmpty()) {
                    message.setText("Enter a name for the save.");
                } else if (confirmAndSave(slot, slots, askOverwrite)) {
                    dialog.dispose();
                } else {
                    message.setText(GuiText.NOT_SAVED);
                }
            } else if (list.getSelectedValue() != null) {
                String slot = list.getSelectedValue().getSlot();
                dialog.dispose();
                loadSlot(slot);
            }
        };
        list.addListSelectionListener(event -> {
            if (saving && list.getSelectedValue() != null) {
                slotField.setText(list.getSelectedValue().getSlot());
            }
            confirm.setEnabled(saving || list.getSelectedValue() != null);
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && !saving) {
                    choose.run();
                }
            }
        });
        confirm.setEnabled(saving);
        confirm.addActionListener(event -> choose.run());
        slotField.addActionListener(event -> choose.run());
        cancel.addActionListener(event -> dialog.dispose());

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        content.add(new JLabel("Saved games (newest first):"), BorderLayout.NORTH);
        content.add(new JScrollPane(list), BorderLayout.CENTER);
        JPanel south = new JPanel();
        south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));
        if (saving) {
            JPanel name = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            name.add(new JLabel("Save as:"));
            name.add(slotField);
            name.add(new JLabel("(letters, digits, - and _)"));
            name.setAlignmentX(Component.LEFT_ALIGNMENT);
            south.add(name);
        }
        message.setAlignmentX(Component.LEFT_ALIGNMENT);
        south.add(message);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(cancel);
        buttons.add(confirm);
        buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
        south.add(buttons);
        content.add(south, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(confirm);
        dialog.pack();
        dialog.setLocationRelativeTo(frame);
        saveDialog = dialog;
        dialog.setVisible(true);
    }

    private boolean askOverwrite(String slot) {
        return JOptionPane.showConfirmDialog(saveDialog != null && saveDialog.isShowing() ? saveDialog : frame,
                "A save named \"" + slot + "\" already exists. Overwrite it?", "Overwrite save?",
                JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
    }

    // ---- running controller calls -------------------------------------------------------------------

    /**
     * Runs a controller call on the worker thread with the input disabled until it is done; then shows the
     * turn's quest updates and, as the CLI does, a hint when the call started a conversation, and refreshes
     * the event log and the journal. Anything the controller throws is shown as an error, so the window
     * never breaks.
     */
    private void runOnWorker(Runnable call) {
        pendingCalls++;
        busy = true;
        updateControls();
        try {
            worker.execute(() -> {
                try {
                    boolean wasTalking = controller.getState().getMode() instanceof DialogueMode;
                    call.run();
                    GameState state = controller.getState();
                    if (!wasTalking && !state.isGameOver() && state.getMode() instanceof DialogueMode dialogue) {
                        pendingNotices.add("You are talking to " + dialogue.getNpc().getName()
                                + ". Say something, or type leave to end the conversation.");
                    }
                } catch (RuntimeException unexpected) {
                    showError("That did not work: " + unexpected.getMessage());
                } finally {
                    flushNotices();
                    EventTableModel history = null;
                    String journal = null;
                    try {
                        history = history();
                        journal = journal(controller.getState());
                    } catch (RuntimeException unreadable) {
                        showError("That did not work: " + unreadable.getMessage());
                    }
                    EventTableModel shownHistory = history;
                    String shownJournal = journal;
                    onEdt(() -> {
                        if (shownHistory != null) {
                            showHistory(shownHistory);
                            showJournal(shownJournal);
                        }
                        pendingCalls--;
                        busy = pendingCalls > 0;
                        updateControls();
                        JTextField focus = shownModePanel == dialoguePanel ? dialogueField : inputField;
                        if (currentCard.equals(GAME_CARD) && focus.isEnabled()) {
                            focus.requestFocusInWindow();
                        }
                    });
                }
            });
        } catch (RejectedExecutionException closed) {
            pendingCalls--;
            busy = pendingCalls > 0;
            updateControls();
        }
    }

    private void flushNotices() {
        List<String> notices;
        synchronized (pendingNotices) {
            notices = new ArrayList<>(pendingNotices);
            pendingNotices.clear();
        }
        notices.forEach(this::showNotice);
    }

    // ---- updating the panels (on the event thread) ----------------------------------------------------

    /**
     * Shows a copy of the state in every panel.
     *
     * @param loaded whether it comes from {@link #render} (a new or loaded game)
     */
    private void apply(GuiState copy, boolean loaded) {
        List<String> selectedPath = selectedNames();
        String selectedHere = itemsHereList.getSelectedValue() == null ? null : itemsHereList.getSelectedValue().name();
        shown = copy;
        statusLabel.setText(copy.getStatusLine());
        modeLabel.setText(copy.getModeLine().isEmpty() ? " " : copy.getModeLine());
        promptLabel.setText(copy.getPrompt());
        mapPanel.setModel(copy.getMap());
        inventoryTree.setModel(copy.getInventory());
        for (int row = 0; row < inventoryTree.getRowCount(); row++) {
            inventoryTree.expandRow(row);
        }
        reselect(selectedPath);
        weightBar.setMaximum((int) Math.round(copy.getMaxWeight() * WEIGHT_SCALE));
        weightBar.setValue((int) Math.round(Math.min(copy.getWeight(), copy.getMaxWeight()) * WEIGHT_SCALE));
        weightBar.setString(GuiText.weightText(copy.getWeight(), copy.getMaxWeight()));
        DefaultListModel<InventoryTreeModel.ItemNode> here = new DefaultListModel<>();
        copy.getItemsHere().forEach(here::addElement);
        itemsHereList.setModel(here);
        for (int index = 0; index < here.size(); index++) {
            if (here.get(index).name().equals(selectedHere)) {
                itemsHereList.setSelectedIndex(index);
                break;
            }
        }
        DefaultListModel<Quest> quests = new DefaultListModel<>();
        copy.getQuests().getQuests().forEach(quests::addElement);
        questList.setModel(quests);
        if (!copy.getPeopleHere().equals(shownPeople)) {
            showPeople(copy.getPeopleHere());
        }
        showModePanels(copy, loaded);
        updateControls();
    }

    /** Swaps the dialogue or the combat panel in above the story panel, by mode, and fills it. */
    private void showModePanels(GuiState copy, boolean loaded) {
        DialogueState dialogue = copy.getDialogue();
        if (conversation.follow(dialogue, loaded)) {
            hideQuestCard();
            showConversation();
        }
        if (dialogue.talking()) {
            dialogueHeading.setText(dialogue.heading());
            portrait.setInitial(dialogue.initial());
        }
        CombatSummary combat = copy.getCombat();
        if (combat.isFighting()) {
            showFighter(playerHpLabel, playerHpBar, combat.getPlayer());
            showFighter(enemyHpLabel, enemyHpBar, combat.getEnemy());
            diceArea.setText(combat.getLastRound().isEmpty() ? "No rolls yet." : String.join("\n", combat.getLastRound()));
        }
        JComponent wanted = combat.isFighting() ? combatPanel : dialogue.talking() ? dialoguePanel : null;
        if (wanted != shownModePanel) {
            modeHolder.removeAll();
            if (wanted != null) {
                modeHolder.add(wanted, BorderLayout.CENTER);
            }
            modeHolder.setVisible(wanted != null);
            shownModePanel = wanted;
            modeHolder.revalidate();
            modeHolder.repaint();
        }
    }

    /** The bar shows the HP; its text ("20 / 24 HP") is in the label, readable in every look and feel. */
    private static void showFighter(JLabel label, JProgressBar bar, CombatSummary.Fighter fighter) {
        String hp = fighter.hp() + " / " + fighter.maxHp() + " HP";
        label.setText(fighter.name() + ": " + hp);
        bar.setMaximum(Math.max(1, fighter.maxHp()));
        bar.setValue(fighter.hp());
        bar.setString(hp);
    }

    /** One Talk button per person here (F03: a click on the NPC starts the conversation). */
    private void showPeople(List<DialogueState.Person> people) {
        shownPeople = people;
        peoplePanel.removeAll();
        talkButtons.clear();
        peoplePanel.add(new JLabel(people.isEmpty() ? "Nobody else is here." : "People here:"));
        for (DialogueState.Person person : people) {
            JButton talk = new JButton("Talk to " + person.name());
            talk.setToolTipText(person.label());
            talk.addActionListener(event -> act(new ActionProposal(ActionType.TALK, person.name(), null),
                    "talk to " + person.name()));
            talkButtons.add(talk);
            peoplePanel.add(talk);
        }
        peoplePanel.revalidate();
        peoplePanel.repaint();
    }

    private void showConversation() {
        conversationArea.setText(conversation.text());
        conversationArea.setCaretPosition(conversationArea.getDocument().getLength());
    }

    private void hideQuestCard() {
        offeredQuest = null;
        questCardPanel.setVisible(false);
        questCardText.setText("");
        dialoguePanel.revalidate();
        updateControls();
    }

    private void showHistory(EventTableModel history) {
        eventTable.setModel(history);
        sizeEventColumns();
        eventCountLabel.setText(history.isCapped()
                ? "Newest first; the " + EventTableModel.DEFAULT_CAP + " most recent."
                : "Newest first; " + history.getRowCount() + (history.getRowCount() == 1 ? " event." : " events."));
    }

    private void sizeEventColumns() {
        int[] widths = {45, 70, 80, 420};
        for (int column = 0; column < widths.length && column < eventTable.getColumnCount(); column++) {
            eventTable.getColumnModel().getColumn(column).setPreferredWidth(widths[column]);
        }
    }

    private void showJournal(String journal) {
        journalArea.setText(journal);
        journalArea.setCaretPosition(0);
    }

    private void updateControls() {
        ControlRules rules = shown.getRules();
        boolean send = rules.enabled(ControlRules.Control.SEND, busy, endingShown);
        inputField.setEnabled(send);
        sendButton.setEnabled(send);
        mapPanel.setMoveEnabled(rules.enabled(ControlRules.Control.MOVE, busy, endingShown));
        boolean carried = selectedCarried() != null;
        useButton.setEnabled(carried && rules.enabled(ControlRules.Control.USE, busy, endingShown));
        equipButton.setEnabled(carried && rules.enabled(ControlRules.Control.EQUIP, busy, endingShown));
        dropButton.setEnabled(carried && rules.enabled(ControlRules.Control.DROP, busy, endingShown));
        takeButton.setEnabled(itemsHereList.getSelectedValue() != null
                && rules.enabled(ControlRules.Control.TAKE, busy, endingShown));
        saveItem.setEnabled(rules.enabled(ControlRules.Control.SAVE, busy, endingShown));
        boolean load = rules.enabled(ControlRules.Control.LOAD, busy, endingShown);
        boolean newGame = rules.enabled(ControlRules.Control.NEW_GAME, busy, endingShown);
        loadItem.setEnabled(load);
        newGameLoadButton.setEnabled(load);
        endLoadButton.setEnabled(load);
        newGameItem.setEnabled(newGame);
        endNewGameButton.setEnabled(newGame);
        startButton.setEnabled(!busy && allocation().isValid());
        boolean say = rules.enabled(ControlRules.PanelControl.SAY, busy, endingShown);
        dialogueField.setEnabled(say);
        dialogueSendButton.setEnabled(say);
        leaveButton.setEnabled(rules.enabled(ControlRules.PanelControl.LEAVE, busy, endingShown));
        boolean answer = offeredQuest != null && rules.enabled(ControlRules.PanelControl.ANSWER, busy, endingShown);
        acceptButton.setEnabled(answer);
        declineButton.setEnabled(answer);
        attackButton.setEnabled(rules.enabled(ControlRules.PanelControl.ATTACK, busy, endingShown));
        fleeButton.setEnabled(rules.enabled(ControlRules.PanelControl.FLEE, busy, endingShown));
        boolean talk = rules.enabled(ControlRules.PanelControl.TALK, busy, endingShown);
        talkButtons.forEach(button -> button.setEnabled(talk));
    }

    /** Live validation of the New Game screen (UC01 3a): the points left, the problem and the highlighted field. */
    private void updateAllocation() {
        StatAllocation allocation = allocation();
        pointsLabel.setText(allocation.pointsText());
        pointsLabel.setForeground(allocation.pointsRemaining() == 0 ? UIManager.getColor("Label.foreground") : PROBLEM);
        nameField.setBorder(allocation.hasName() ? nameBorder
                : BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(PROBLEM), nameBorder));
        String problem = allocation.problem();
        showNewGameMessage(problem == null ? " " : problem);
        startButton.setEnabled(!busy && allocation.isValid());
    }

    private void showNewGameMessage(String message) {
        newGameMessage.setText(message);
    }

    private StatAllocation allocation() {
        return new StatAllocation(nameField.getText(), (CharacterClass) classBox.getSelectedItem(),
                strengthSlider.getValue(), agilitySlider.getValue(), intelligenceSlider.getValue());
    }

    private InventoryTreeModel.ItemNode selectedCarried() {
        TreePath path = inventoryTree.getSelectionPath();
        if (path == null) {
            return null;
        }
        Object node = path.getLastPathComponent();
        return node instanceof DefaultMutableTreeNode tree && tree.getUserObject() instanceof InventoryTreeModel.ItemNode item
                ? item : null;
    }

    /** The item names from the root down to the selected node, to select the same item after a refresh. */
    private List<String> selectedNames() {
        TreePath path = inventoryTree.getSelectionPath();
        List<String> names = new ArrayList<>();
        if (path == null) {
            return names;
        }
        for (Object part : path.getPath()) {
            if (part instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof InventoryTreeModel.ItemNode item) {
                names.add(item.name());
            }
        }
        return names;
    }

    private void reselect(List<String> names) {
        if (names.isEmpty()) {
            return;
        }
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) ((DefaultTreeModel) inventoryTree.getModel()).getRoot();
        for (String name : names) {
            DefaultMutableTreeNode next = null;
            for (int index = 0; index < node.getChildCount(); index++) {
                DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(index);
                if (child.getUserObject() instanceof InventoryTreeModel.ItemNode item && item.name().equals(name)) {
                    next = child;
                    break;
                }
            }
            if (next == null) {
                return;
            }
            node = next;
        }
        inventoryTree.setSelectionPath(new TreePath(node.getPath()));
    }

    private void append(String text) {
        storyArea.append(text + "\n");
        storyArea.setCaretPosition(storyArea.getDocument().getLength());
    }

    private void showCard(String card) {
        currentCard = card;
        cards.show(cardPanel, card);
    }

    private static JSlider statSlider() {
        JSlider slider = new JSlider(StatAllocation.MIN, StatAllocation.MAX, StatAllocation.MIN);
        slider.setMajorTickSpacing(1);
        slider.setPaintTicks(true);
        slider.setPaintLabels(true);
        slider.setSnapToTicks(true);
        return slider;
    }

    private static void addRow(JPanel form, GridBagConstraints at, int row, String label, JComponent field) {
        at.gridx = 0;
        at.gridy = row;
        form.add(new JLabel(label), at);
        at.gridx = 1;
        form.add(field, at);
    }

    private static JPanel titled(String title, BorderLayout layout) {
        JPanel panel = new JPanel(layout);
        panel.setBorder(BorderFactory.createTitledBorder(title));
        return panel;
    }

    private static void onEdt(Runnable update) {
        if (SwingUtilities.isEventDispatchThread()) {
            update.run();
        } else {
            SwingUtilities.invokeLater(update);
        }
    }

    private static <T> T onEdtAndWait(Supplier<T> create) {
        if (SwingUtilities.isEventDispatchThread()) {
            return create.get();
        }
        List<T> result = new ArrayList<>(1);
        try {
            SwingUtilities.invokeAndWait(() -> result.add(create.get()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while creating the window", interrupted);
        } catch (InvocationTargetException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Could not create the window", cause);
        }
        return result.get(0);
    }

    // ---- for the tests (same package) ---------------------------------------------------------------

    /** Waits until the worker has run every pending controller call and the event thread has shown the results. */
    void awaitIdle() throws Exception {
        worker.submit(() -> { }).get(20, TimeUnit.SECONDS);
        SwingUtilities.invokeAndWait(() -> { });
    }

    void setOverwriteConfirmer(Predicate<String> confirmer) {
        this.overwriteConfirmer = Objects.requireNonNull(confirmer, "confirmer");
    }

    JFrame frame() {
        return frame;
    }

    String currentCard() {
        return currentCard;
    }

    JTextField nameField() {
        return nameField;
    }

    JComboBox<CharacterClass> classBox() {
        return classBox;
    }

    List<JSlider> statSliders() {
        return List.of(strengthSlider, agilitySlider, intelligenceSlider);
    }

    JLabel pointsLabel() {
        return pointsLabel;
    }

    JLabel newGameMessage() {
        return newGameMessage;
    }

    JButton startButton() {
        return startButton;
    }

    JTextArea storyArea() {
        return storyArea;
    }

    JTextField inputField() {
        return inputField;
    }

    JButton sendButton() {
        return sendButton;
    }

    JLabel statusLabel() {
        return statusLabel;
    }

    JLabel promptLabel() {
        return promptLabel;
    }

    MapPanel mapPanel() {
        return mapPanel;
    }

    JTree inventoryTree() {
        return inventoryTree;
    }

    JProgressBar weightBar() {
        return weightBar;
    }

    JList<InventoryTreeModel.ItemNode> itemsHereList() {
        return itemsHereList;
    }

    JButton takeButton() {
        return takeButton;
    }

    JButton useButton() {
        return useButton;
    }

    JButton equipButton() {
        return equipButton;
    }

    JButton dropButton() {
        return dropButton;
    }

    JList<Quest> questList() {
        return questList;
    }

    JMenuItem newGameItem() {
        return newGameItem;
    }

    JMenuItem saveItem() {
        return saveItem;
    }

    JMenuItem loadItem() {
        return loadItem;
    }

    JDialog saveDialog() {
        return saveDialog;
    }

    JLabel endTitle() {
        return endTitle;
    }

    JTextArea epilogueArea() {
        return epilogueArea;
    }

    JLabel endStatistics() {
        return endStatistics;
    }

    JButton endNewGameButton() {
        return endNewGameButton;
    }

    JButton endLoadButton() {
        return endLoadButton;
    }

    JPanel dialoguePanel() {
        return dialoguePanel;
    }

    JPanel combatPanel() {
        return combatPanel;
    }

    /** "dialogue", "combat" or "none": which mode panel is above the story panel. */
    String modePanelName() {
        return shownModePanel == dialoguePanel ? "dialogue" : shownModePanel == combatPanel ? "combat" : "none";
    }

    JLabel dialogueHeading() {
        return dialogueHeading;
    }

    JTextArea conversationArea() {
        return conversationArea;
    }

    JTextField dialogueField() {
        return dialogueField;
    }

    JButton dialogueSendButton() {
        return dialogueSendButton;
    }

    JButton leaveButton() {
        return leaveButton;
    }

    JPanel questCardPanel() {
        return questCardPanel;
    }

    JLabel questCardText() {
        return questCardText;
    }

    JButton acceptButton() {
        return acceptButton;
    }

    JButton declineButton() {
        return declineButton;
    }

    JProgressBar playerHpBar() {
        return playerHpBar;
    }

    JProgressBar enemyHpBar() {
        return enemyHpBar;
    }

    JTextArea diceArea() {
        return diceArea;
    }

    JButton attackButton() {
        return attackButton;
    }

    JButton fleeButton() {
        return fleeButton;
    }

    List<JButton> talkButtons() {
        return List.copyOf(talkButtons);
    }

    JTable eventTable() {
        return eventTable;
    }

    JComboBox<HistoryFilter> filterBox() {
        return filterBox;
    }

    JCheckBox showAllBox() {
        return showAllBox;
    }

    JTextArea journalArea() {
        return journalArea;
    }

    /** The quest text a quest-list cell shows. */
    String questText(Quest quest) {
        QuestListModel.Entry entry = shown.getQuests().entryOf(quest);
        return entry == null ? null : entry.toHtml();
    }

    /** The dialogue panel's portrait area (F03): a drawn circle with the NPC's initial, no image file. */
    private static final class Portrait extends JComponent {

        private static final Color FILL = new Color(120, 96, 70);
        private String initial = "";

        Portrait() {
            setPreferredSize(new Dimension(76, 76));
        }

        void setInitial(String initial) {
            this.initial = initial;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int size = Math.min(getWidth(), getHeight()) - 6;
                int x = (getWidth() - size) / 2;
                int y = 3;
                g.setColor(FILL);
                g.fillOval(x, y, size, size);
                g.setColor(Color.WHITE);
                g.setFont(getFont().deriveFont(Font.BOLD, size / 2f));
                FontMetrics metrics = g.getFontMetrics();
                g.drawString(initial, x + (size - metrics.stringWidth(initial)) / 2,
                        y + (size - metrics.getHeight()) / 2 + metrics.getAscent());
            } finally {
                g.dispose();
            }
        }
    }
}
