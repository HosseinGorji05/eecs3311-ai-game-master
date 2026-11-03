package presentation;

import engine.character.CharacterClass;
import engine.character.Player;
import engine.character.Stats;
import engine.commands.ActionProposal;
import engine.commands.ActionType;
import engine.core.CombatMode;
import engine.core.DialogueMode;
import engine.core.GameState;
import engine.events.EventType;
import engine.events.GameEvent;
import engine.events.GameEventListener;
import engine.persistence.SaveSlotInfo;
import engine.quests.Quest;
import engine.world.Location;
import engine.world.NPC;
import engine.world.Region;

import java.io.InputStream;
import java.io.PrintStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
 * <p>Command table ({@link #dispatch}): {@code map} and {@code quests} show the situation with the
 * quest log (active and finished quests, their giver, progress and reward), {@code journal} the story
 * so far (the story memory), {@code history [type]} the event history, {@code accept} and
 * {@code decline} answer the quest last offered, {@code flee} and {@code leave} are sent as fixed
 * actions, {@code save <name>}, {@code load <name>} and {@code saves} save, load and list games
 * (saving over an existing name asks for a yes or no first), {@code help} lists the commands and
 * {@code quit} ends the game; any other line is said to the NPC in a conversation, and otherwise
 * played as a natural-language command. In a conversation {@code bye} and {@code goodbye} also leave,
 * and the prompt names the NPC. After the player dies, the session goes on so a save can be loaded
 * (UC11); every other command answers that the game is over.
 */
public class CliGameView implements GameView, GameEventListener {

    /** Stat allocation per class for the minimal character creation (strength, agility, intelligence). */
    static final Map<CharacterClass, Stats> PRESET_STATS = Map.of(
            CharacterClass.WARRIOR, new Stats(5, 3, 2),
            CharacterClass.ROGUE, new Stats(2, 6, 2),
            CharacterClass.MAGE, new Stats(2, 3, 5));

    private static final int HISTORY_COUNT = 20;
    private static final DateTimeFormatter SAVE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
            .withZone(ZoneId.systemDefault());
    static final String GAME_OVER_LINE = "You have died. Game over. Type load <name> to continue from a save, or quit.";
    private static final String HELP = """
            Commands: type what you want to do, e.g. "go north", "go to the tavern", "take shed key",
            "equip sword", "use healing potion", "drop bread", "put bread in pouch", "look", "inventory",
            "talk to Marta", "attack wolf", "flee", or several at once: "take shed key and go south".
            In a conversation, everything you type is said to the person; leave (or bye) ends it.
            When someone offers you a quest, type accept or decline.
            save <name> saves the game (not during combat), load <name> continues a saved game, saves lists them.
            Also: map, quests, journal, history, history <type>, help, quit.""";
    private static final Set<String> LEAVE_WORDS = Set.of("leave", "bye", "goodbye");

    private final GameController controller;
    private final Scanner in;
    private final PrintStream out;
    private final List<String> pendingNotices = new ArrayList<>();
    private Quest offeredQuest;
    private boolean running;
    private boolean deathAnnounced;

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
            out.print(controller.getState().getMode() instanceof DialogueMode dialogue
                    ? "(to " + dialogue.getNpc().getName() + ") > " : "> ");
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
        boolean talking = controller.getState().getMode() instanceof DialogueMode;
        TurnResult result = null;
        if (words.length == 1 && (first.equals("quit") || first.equals("exit"))) {
            running = false;
        } else if (words.length == 1 && first.equals("help")) {
            out.println(HELP);
        } else if (words.length == 1 && (first.equals("map") || first.equals("quests"))) {
            render(controller.getState());
        } else if (words.length == 1 && first.equals("journal")) {
            out.println(controller.getJournal());
        } else if (first.equals("history") && words.length <= 2) {
            showHistory(words.length == 1 ? null : words[1]);
        } else if (words.length == 1 && (first.equals("flee") || first.equals("leave")
                || talking && LEAVE_WORDS.contains(first))) {
            ActionType type = first.equals("flee") ? ActionType.FLEE : ActionType.LEAVE;
            result = controller.performAction(new ActionProposal(type, null, null));
        } else if (words.length == 1 && (first.equals("accept") || first.equals("decline"))) {
            Quest quest = offeredQuest;
            offeredQuest = null;
            controller.respondToQuest(quest, first.equals("accept"));
        } else if ((first.equals("save") || first.equals("load")) && words.length <= 2) {
            if (words.length == 1) {
                showError("Type " + first + " <name>, e.g. " + first + " mygame.");
            } else if (first.equals("save")) {
                save(words[1]);
            } else {
                offeredQuest = null;
                controller.loadGame(words[1]);
                deathAnnounced = !controller.getState().getPlayer().isAlive();
            }
        } else if (words.length == 1 && first.equals("saves")) {
            showSaves();
        } else if (talking) {
            controller.sendDialogue(line);
        } else {
            result = controller.submitCommand(line);
        }
        if (!talking && controller.getState().getMode() instanceof DialogueMode dialogue) {
            showNotice("You are talking to " + dialogue.getNpc().getName() + ". Say something, or type leave to end the conversation.");
        }
        if (result != null && result.isGameOver() && !deathAnnounced) {
            flushNotices();
            out.println(GAME_OVER_LINE);
            deathAnnounced = true;
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
        } else if (state.getMode() instanceof DialogueMode dialogue) {
            out.println("Talking to " + dialogue.getNpc().describe() + ".");
        }
        List<Quest> quests = state.getQuestLog().allQuests();
        out.println("Quests:" + (quests.isEmpty() ? " none" : ""));
        for (Quest quest : quests) {
            out.println("- " + quest.getTitle() + " (" + quest.getStatus().name().toLowerCase(Locale.ROOT)
                    + ", from " + giverName(state, quest) + "): " + describeObjectives(quest, true)
                    + "; reward: " + quest.getReward().describe());
        }
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

    /** Prints the offer and remembers the quest, so {@code accept} and {@code decline} can answer it. */
    @Override
    public void showQuestOffer(Quest quest) {
        offeredQuest = quest;
        out.println(giverName(controller.getState(), quest) + " offers you a quest: " + quest.getTitle());
        out.println("  " + quest.getDescription());
        out.println("  Objectives: " + describeObjectives(quest, false));
        out.println("  Reward: " + quest.getReward().describe());
        out.println("Type accept or decline.");
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

    /** Saves in the slot; if a save of that name exists (and saving is allowed), asks first. */
    private void save(String slot) {
        boolean exists = controller.listSaves().stream().anyMatch(info -> info.getSlot().equalsIgnoreCase(slot));
        if (exists && controller.getState().canSave()) {
            out.print("A save named \"" + slot + "\" already exists. Overwrite it? (yes/no) ");
            String answer = in.hasNextLine() ? in.nextLine().trim().toLowerCase(Locale.ROOT) : "";
            if (!answer.equals("yes") && !answer.equals("y")) {
                showNotice("Not saved. Type save with another name to keep both.");
                return;
            }
        }
        controller.saveGame(slot);
    }

    private void showSaves() {
        List<SaveSlotInfo> slots = controller.listSaves();
        if (slots.isEmpty()) {
            out.println("No saves yet.");
            return;
        }
        out.println("Saves (newest first):");
        for (SaveSlotInfo slot : slots) {
            out.println("- " + slot.getSlot() + ": " + slot.getCharacterName() + " at " + slot.getLocationName()
                    + ", saved " + SAVE_TIME.format(slot.getSavedAt()));
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

    /** "fetch bread 0/1, visit old-mill 1/1" with progress, or "fetch bread (1)" without. */
    private static String describeObjectives(Quest quest, boolean progress) {
        return quest.getObjectives().stream()
                .map(objective -> objective.getType().name().toLowerCase(Locale.ROOT) + " " + objective.getTargetId()
                        + (progress ? " " + objective.getProgress() + "/" + objective.getRequired()
                                : " (" + objective.getRequired() + ")"))
                .collect(Collectors.joining(", "));
    }

    private static String giverName(GameState state, Quest quest) {
        NPC giver = quest.getGiverId() == null ? null : state.getWorld().findNpc(quest.getGiverId());
        return giver != null ? giver.getName() : quest.getGiverId() != null ? quest.getGiverId() : "nobody";
    }
}
