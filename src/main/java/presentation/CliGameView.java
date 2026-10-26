package presentation;

import engine.character.CharacterClass;
import engine.character.Player;
import engine.character.Stats;
import engine.commands.ActionProposal;
import engine.commands.ActionType;
import engine.core.CombatMode;
import engine.core.GameState;
import engine.events.EventType;
import engine.events.GameEvent;
import engine.events.GameEventListener;
import engine.quests.Quest;
import engine.world.Location;
import engine.world.Region;

import java.io.InputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Scanner;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The text interface: reads lines, sends them to the {@link GameController} and prints what comes
 * back. Quest notifications from the event bus are printed after the turn's narration.
 *
 * <p>Pattern roles: <b>View</b> of MVC and <b>ConcreteObserver</b> of the Observer pattern.
 *
 * <p>Command table ({@link #dispatch}): {@code map} and {@code quests} show the situation,
 * {@code history [type]} the event history, {@code flee} and {@code leave} are sent as fixed
 * actions, {@code help} lists the commands and {@code quit} ends the game; any other line is played
 * as a natural-language command. The journal, saving, loading and quest offers are not available
 * yet.
 */
public class CliGameView implements GameView, GameEventListener {

    /** Stat allocation per class for the minimal character creation (strength, agility, intelligence). */
    static final Map<CharacterClass, Stats> PRESET_STATS = Map.of(
            CharacterClass.WARRIOR, new Stats(5, 3, 2),
            CharacterClass.ROGUE, new Stats(2, 6, 2),
            CharacterClass.MAGE, new Stats(2, 3, 5));

    private static final int HISTORY_COUNT = 20;
    private static final Set<String> NOT_AVAILABLE_YET = Set.of("journal", "save", "load", "saves", "accept", "decline");
    private static final String HELP = """
            Commands: type what you want to do, e.g. "go north", "go to the tavern", "take shed key",
            "attack wolf", "flee", or several at once: "take shed key and go south".
            Also: map, quests, history, history <type>, help, quit.""";

    private final GameController controller;
    private final Scanner in;
    private final PrintStream out;
    private final List<String> pendingNotices = new ArrayList<>();
    private boolean running;

    public CliGameView(GameController controller, InputStream in, PrintStream out) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.in = new Scanner(Objects.requireNonNull(in, "in"));
        this.out = Objects.requireNonNull(out, "out");
    }

    /** Creates the character, starts the game and plays until "quit", the end of input or death. */
    public void readLoop() {
        out.println("=== AI Game Master ===");
        if (!createCharacter()) {
            out.println("Goodbye.");
            return;
        }
        controller.startGame();
        out.println(HELP);
        running = true;
        while (running) {
            out.print("> ");
            if (!in.hasNextLine()) {
                break;
            }
            String line = in.nextLine().trim();
            if (!line.isEmpty()) {
                try {
                    dispatch(line);
                } catch (IllegalArgumentException unexpected) {
                    showError("That did not work: " + unexpected.getMessage());
                }
                flushNotices();
            }
        }
        out.println("Goodbye.");
    }

    private void dispatch(String line) {
        String[] words = line.toLowerCase(Locale.ROOT).split("\\s+");
        String first = words[0];
        TurnResult result = null;
        if (words.length == 1 && (first.equals("quit") || first.equals("exit"))) {
            running = false;
        } else if (words.length == 1 && first.equals("help")) {
            out.println(HELP);
        } else if (words.length == 1 && (first.equals("map") || first.equals("quests"))) {
            render(controller.getState());
        } else if (first.equals("history") && words.length <= 2) {
            showHistory(words.length == 1 ? null : words[1]);
        } else if (words.length == 1 && (first.equals("flee") || first.equals("leave"))) {
            ActionType type = first.equals("flee") ? ActionType.FLEE : ActionType.LEAVE;
            result = controller.performAction(new ActionProposal(type, null, null));
        } else if (NOT_AVAILABLE_YET.contains(first)) {
            showError("\"" + first + "\" is not available yet.");
        } else {
            result = controller.submitCommand(line);
        }
        if (result != null && result.isGameOver()) {
            flushNotices();
            out.println("You have died. Game over.");
            running = false;
        }
    }

    @Override
    public void render(GameState state) {
        Player player = state.getPlayer();
        out.println(state.currentLocation().describe());
        out.printf(Locale.ROOT, "%s: %d/%d HP | Gold %d | XP %d | Carrying %.1f of %.1f kg%n", player.getName(),
                player.getHp(), player.getMaxHp(), player.getGold(), player.getXp(),
                player.getInventory().totalWeight(), player.getInventory().getMaxWeight());
        if (state.getMode() instanceof CombatMode combat) {
            out.println("In combat with the " + combat.getEnemy().getName() + ".");
        }
        List<Quest> quests = state.getQuestLog().activeQuests();
        out.println("Quests: " + (quests.isEmpty() ? "none" : quests.stream()
                .map(CliGameView::describeQuest).collect(Collectors.joining("; "))));
        List<String> visited = new ArrayList<>();
        for (Region region : state.getWorld().getRegions()) {
            for (Location location : region.getLocations()) {
                if (location.isVisited()) {
                    visited.add(location.getName());
                }
            }
        }
        out.println("Visited: " + String.join(", ", visited));
    }

    @Override
    public void showNarration(String text) {
        out.println(text);
    }

    @Override
    public void showNotice(String text) {
        out.println("(" + text + ")");
    }

    @Override
    public void showError(String message) {
        out.println("! " + message);
    }

    /** Remembers quest updates and prints them after the turn's narration. */
    @Override
    public void onEvent(GameEvent event) {
        if (event.getType() == EventType.QUEST) {
            pendingNotices.add(event.getDescription());
        }
    }

    /** Asks for a name and a class until both are valid; {@code false} if the input ends first. */
    private boolean createCharacter() {
        String name = null;
        while (name == null) {
            out.print("Your name: ");
            if (!in.hasNextLine()) {
                return false;
            }
            String line = in.nextLine().trim();
            name = line.isEmpty() ? null : line;
        }
        CharacterClass cls = null;
        while (cls == null) {
            out.print("Class (warrior, rogue, mage): ");
            if (!in.hasNextLine()) {
                return false;
            }
            cls = characterClass(in.nextLine());
            if (cls == null) {
                showError("Please choose warrior, rogue or mage.");
            }
        }
        controller.newGame(name, cls, PRESET_STATS.get(cls));
        out.println(name + " the " + cls.name().toLowerCase(Locale.ROOT) + " sets out.");
        return true;
    }

    private void showHistory(String typeName) {
        List<GameEvent> events;
        if (typeName == null) {
            events = controller.getRecentHistory(HISTORY_COUNT);
        } else {
            try {
                events = controller.getHistory(EventType.valueOf(typeName.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException unknownType) {
                showError("Unknown event type. Valid types: " + Arrays.stream(EventType.values())
                        .map(type -> type.name().toLowerCase(Locale.ROOT)).collect(Collectors.joining(", ")) + ".");
                return;
            }
        }
        if (events.isEmpty()) {
            out.println("No events yet.");
        }
        for (GameEvent event : events) {
            out.println("[turn " + event.getTurn() + "] " + event.getType().name().toLowerCase(Locale.ROOT)
                    + ": " + event.getDescription());
        }
    }

    private void flushNotices() {
        pendingNotices.forEach(this::showNotice);
        pendingNotices.clear();
    }

    private static CharacterClass characterClass(String text) {
        try {
            return CharacterClass.valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException notAClass) {
            return null;
        }
    }

    private static String describeQuest(Quest quest) {
        return quest.getTitle() + " (" + quest.getObjectives().stream()
                .map(objective -> objective.getTargetId() + " " + objective.getProgress() + "/" + objective.getRequired())
                .collect(Collectors.joining(", ")) + ")";
    }
}
