package presentation;

import engine.character.Player;
import engine.core.CombatMode;
import engine.core.DialogueMode;
import engine.core.Ending;
import engine.core.EndingType;
import engine.core.GameState;
import engine.persistence.SaveSlotInfo;
import engine.quests.Quest;
import engine.world.World;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** The texts the GUI shows, formatted without a display so they can be tested. */
final class GuiText {

    /** Shown in the status area before a game starts. */
    static final String NO_GAME = "No game yet.";
    /** The input label outside a conversation. */
    static final String COMMAND_PROMPT = "What do you do?";
    /** Shown after a declined overwrite (the CLI's wording, for a dialog). */
    static final String NOT_SAVED = "Not saved. Choose another name to keep both.";
    /** The story panel's help; one line per paragraph, so the panel's word wrap lays it out. */
    static final String HELP = """
            Type what you want to do and press Enter, e.g. "go north", "take shed key", "equip sword", \
            "use healing potion", "talk to Marta", "attack wolf", "flee". Click a neighbouring place on the map \
            to walk there, a Talk button under the map to talk to someone there; use the inventory buttons for \
            your items and the Game menu to save or load.
            In a conversation, the panel above shows the person; everything you type is said to them, and Leave \
            (or typing leave or bye) ends it. A quest offer appears there as a card: Accept or Decline (or type \
            accept or decline). In a fight, the combat panel shows both HP bars and the last dice rolls, with \
            Attack and Flee. The Event log and Journal tabs show what has happened so far.""";

    /** The backstory as the story panel shows it once when the game starts: "Ari's story:" and the text. */
    static String backstoryIntro(String name, String backstory) {
        return name + "'s story:\n" + backstory;
    }

    /** The window title with the language model in use: "AI Game Master - groq (openai/gpt-oss-20b)" or "... - offline". */
    static String windowTitle(String llmDescription) {
        return SwingGameView.TITLE + " - " + llmDescription;
    }

    /** The help with the language model in use at the end; offline, typed commands use the basic keywords. */
    static String help(String llmDescription) {
        return HELP + "\n" + (llmDescription.equals("offline")
                ? "Language model: offline (basic commands only, e.g. \"go north\", \"take bread\")."
                : "Language model: " + llmDescription + ".");
    }

    private static final DateTimeFormatter SAVE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private GuiText() {
    }

    /** "Ari the Rogue | HP 24/24 | Gold 0 | XP 0 | Village Square | Turn 1", or {@link #NO_GAME}. */
    static String statusLine(GameState state) {
        Objects.requireNonNull(state, "state");
        Player player = state.getPlayer();
        if (player == null || state.currentLocation() == null) {
            return NO_GAME;
        }
        return String.format(Locale.ROOT, "%s the %s | HP %d/%d | Gold %d | XP %d | %s | Turn %d", player.getName(),
                className(player), player.getHp(), player.getMaxHp(), player.getGold(), player.getXp(),
                state.currentLocation().getName(), state.getTurn());
    }

    /** "In combat with the wolf (5/8 HP).", "Talking to Brann (blacksmith).", "The game is over." or "". */
    static String modeLine(GameState state) {
        Objects.requireNonNull(state, "state");
        if (state.getPlayer() == null || state.getMode() == null) {
            return "";
        }
        if (state.isGameOver()) {
            return GameController.GAME_OVER;
        }
        if (state.getMode() instanceof CombatMode combat) {
            return "In combat with the " + combat.getEnemy().getName() + " (" + combat.getEnemy().getHp() + "/"
                    + combat.getEnemy().getMaxHp() + " HP).";
        }
        if (state.getMode() instanceof DialogueMode dialogue) {
            return "Talking to " + dialogue.getNpc().describe() + ".";
        }
        return "";
    }

    /** The input label: "Say to Marta:" in a conversation, else {@link #COMMAND_PROMPT}. */
    static String prompt(GameState state) {
        Objects.requireNonNull(state, "state");
        return state.getMode() instanceof DialogueMode dialogue
                ? "Say to " + dialogue.getNpc().getName() + ":" : COMMAND_PROMPT;
    }

    /** "4.5 of 10 kg". */
    static String weightText(double total, double max) {
        return kg(total) + " of " + kg(max) + " kg";
    }

    /** One decimal, without ".0" ("9.5", "10"), as the engine's messages write weights. */
    static String kg(double weight) {
        String text = String.format(Locale.ROOT, "%.1f", weight);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }

    /** "mygame: Ari at Tavern, saved 2026-11-04 10:30" in the given time zone. */
    static String slotLabel(SaveSlotInfo slot, ZoneId zone) {
        return slot.getSlot() + ": " + slot.getCharacterName() + " at " + slot.getLocationName() + ", saved "
                + SAVE_TIME.withZone(zone).format(slot.getSavedAt());
    }

    /** "The End: Heroic ending" (the CLI's heading). */
    static String endingTitle(EndingType type) {
        String name = type.name();
        return "The End: " + name.charAt(0) + name.substring(1).toLowerCase(Locale.ROOT) + " ending";
    }

    /** "Turns played: 12 | Quests completed: 1 | Enemies defeated: 2", in the ending's order. */
    static String statistics(Ending ending) {
        Map<String, Integer> statistics = ending.getStatistics();
        return statistics.entrySet().stream()
                .map(entry -> capitalize(entry.getKey()) + ": " + entry.getValue())
                .collect(Collectors.joining(" | "));
    }

    /** The quest offer as the story panel shows it (the CLI's lines); the dialogue panel shows it as a card too. */
    static String questOffer(Quest quest, World world) {
        String objectives = quest.getObjectives().stream()
                .map(objective -> objective.getType().name().toLowerCase(Locale.ROOT) + " " + objective.getTargetId()
                        + " (" + objective.getRequired() + ")")
                .collect(Collectors.joining(", "));
        return QuestListModel.giverName(quest, world) + " offers you a quest: " + quest.getTitle() + "\n  " + quest.getDescription()
                + "\n  Objectives: " + objectives + "\n  Reward: " + quest.getReward().describe()
                + "\nType accept or decline.";
    }

    /** Escapes {@code & < > "} for text put into a Swing HTML label. */
    static String escapeHtml(String text) {
        StringBuilder escaped = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }

    private static String className(Player player) {
        return capitalize(player.getCharacterClass().name().toLowerCase(Locale.ROOT));
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
