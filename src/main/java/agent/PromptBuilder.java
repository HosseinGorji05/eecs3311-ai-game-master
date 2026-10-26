package agent;

import engine.character.Player;
import engine.core.CombatMode;
import engine.core.GameState;
import engine.world.Location;

import java.util.Map;
import java.util.Objects;

/**
 * Builds prompt text from named templates and a summary of the real game state, so the model only
 * hears about things that exist.
 *
 * <p>Templates use {@code {{name}}} placeholders. The story-memory context of the design
 * ({@code memory}, {@code memoryContext()}) is added together with the memory manager.
 */
public class PromptBuilder {

    /** The templates the game's LLM tasks use. */
    static final Map<String, String> DEFAULT_TEMPLATES = Map.of(
            "interpret.system", """
                    You turn a text-adventure player's input into game actions. Answer with JSON only, \
                    in the form {"actions":[{"type":"take","target":"shed key","secondTarget":null}]}. \
                    Allowed types: move, take, drop, put, use, equip, talk, leave, attack, flee, look, inventory. \
                    A move target is a direction (north, south, east, west) or the name of an adjacent location. \
                    Use only names that appear in the game state. If the input asks for something impossible \
                    or is not an action, answer {"actions":[]}.""",
            "interpret.user", """
                    Game state:
                    {{state}}

                    Player input: {{input}}""");

    private final Map<String, String> templates;

    public PromptBuilder() {
        this(DEFAULT_TEMPLATES);
    }

    public PromptBuilder(Map<String, String> templates) {
        this.templates = Map.copyOf(Objects.requireNonNull(templates, "templates"));
    }

    /** The current location with its items, enemies and exits, the mode and the player's HP. */
    public String stateSummary(GameState state) {
        Objects.requireNonNull(state, "state");
        Location location = state.currentLocation();
        Player player = state.getPlayer();
        String items = location.getItems().isEmpty() ? "none"
                : String.join(", ", location.getItems().stream().map(item -> item.getName()).toList());
        String mode = state.getMode() instanceof CombatMode combat
                ? "in combat with the " + combat.getEnemy().getName()
                : "exploring";
        return "Location: " + location.getName() + "\n"
                + "Items here: " + items + "\n"
                + "Enemies here: " + location.describeEnemies() + "\n"
                + "Exits: " + location.describeExits() + "\n"
                + "Mode: " + mode + "\n"
                + "Player: " + player.getName() + ", " + player.getHp() + "/" + player.getMaxHp() + " HP";
    }

    /**
     * Fills a template's {@code {{name}}} placeholders with {@code vars}.
     *
     * @throws IllegalArgumentException if there is no template with this name, or a placeholder
     *                                  has no value
     */
    public String fromTemplate(String name, Map<String, String> vars) {
        String template = templates.get(name);
        if (template == null) {
            throw new IllegalArgumentException("No prompt template named " + name);
        }
        String text = template;
        for (Map.Entry<String, String> var : vars.entrySet()) {
            text = text.replace("{{" + var.getKey() + "}}", var.getValue());
        }
        if (text.contains("{{")) {
            throw new IllegalArgumentException("Template " + name + " has a placeholder without a value");
        }
        return text;
    }
}
