package agent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import engine.character.Enemy;
import engine.character.Player;
import engine.core.GameState;
import engine.items.Item;
import engine.quests.Objective;
import engine.quests.ObjectiveType;
import engine.quests.Quest;
import engine.quests.Reward;
import engine.world.Location;
import engine.world.NPC;
import engine.world.Region;
import llm.LLMClient;
import llm.LlmRequest;
import llm.LlmResponse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Lets a quest-giving NPC offer a side quest built only from things that exist (F07, SD07).
 *
 * <p>Pattern role: <b>ConcreteClass</b> of the Template Method pattern. The model proposes the quest
 * as one JSON object:
 * <pre>
 * {"title":"...","description":"...","giver":"Brann",
 *  "objectives":[{"type":"fetch|defeat|visit","target":"...","count":1}],
 *  "reward":{"gold":15,"xp":15,"item":null}}
 * </pre>
 * ({@code giver} is optional). The prompt holds the giver's profile, the world, the quests already
 * taken and the story memory focused on the giver. The proposal is data and nothing in it is executed:
 * <ul>
 *   <li>{@link #parse} reads only these fields. It returns {@code null} (an invalid answer, asked
 *       again once) for an objective type other than fetch, defeat or visit, a count that is not a
 *       whole number of at least 1, gold or XP that are not whole non-negative numbers, a missing
 *       field, or any other field (such as {@code "status"} or {@code "progress"}), since a proposal
 *       may not set game state.</li>
 *   <li>{@link #isValid} asks {@link QuestValidator} whether every target exists and can be reached,
 *       the texts fit, the reward item is the giver's, the quest is no duplicate and the giver may
 *       offer it.</li>
 *   <li>{@code toQuest} clamps gold and XP to {@value #REWARD_PER_LEVEL} per player level (UC07 3b)
 *       and uses the game's names for the targets.</li>
 * </ul>
 *
 * <p>Fallback: a template quest built from the current world. There are three templates, tried in
 * turn starting with the one after the giver's previous quest: FETCH the first unowned item lying in
 * another location ({@value #FETCH_GOLD} gold, {@value #FETCH_XP} XP), DEFEAT the first living enemy
 * ({@value #DEFEAT_GOLD} gold, {@value #DEFEAT_XP} XP and the lightest item the giver owns, if any),
 * VISIT the first location not visited yet, else any other location ({@value #VISIT_GOLD} gold,
 * {@value #VISIT_XP} XP). Only a candidate the validator accepts is used, so the fallback never
 * offers an invalid quest; if no candidate is valid there is no offer.
 */
public class QuestGenerator extends LlmTask<NPC, QuestProposal> {

    /** Most gold and most XP a quest may give per player level (Stage 2 tuning number). */
    public static final int REWARD_PER_LEVEL = 25;

    static final int FETCH_GOLD = 15;
    static final int FETCH_XP = 15;
    static final int DEFEAT_GOLD = 25;
    static final int DEFEAT_XP = 25;
    static final int VISIT_GOLD = 10;
    static final int VISIT_XP = 10;

    private static final int MAX_TOKENS = 400;

    private static final Set<String> QUEST_FIELDS = Set.of("title", "description", "objectives", "reward", "giver");
    private static final Set<String> OBJECTIVE_FIELDS = Set.of("type", "target", "count");
    private static final Set<String> REWARD_FIELDS = Set.of("gold", "xp", "item");

    private final QuestValidator questValidator;

    public QuestGenerator(LLMClient llm, PromptBuilder prompts, ResponseParser parser, GameState state) {
        super(llm, prompts, parser, state);
        this.questValidator = new QuestValidator(state);
    }

    /**
     * A quest the giver offers the player (the player is talking to the giver), from the model if it
     * proposes a valid one within two attempts, otherwise from a template.
     *
     * @return the quest, or {@code null} if neither the model nor any template gives a valid quest
     */
    public Quest generate(NPC giver) {
        QuestProposal proposal = run(giver);
        return proposal == null ? null : toQuest(proposal, giver);
    }

    @Override
    protected LlmRequest buildPrompt(NPC giver) {
        Player player = state.getPlayer();
        int max = maxReward();
        String system = prompts.fromTemplate("quest.system", Map.of(
                "name", giver.getName(),
                "role", giver.getRole(),
                "personality", giver.getPersonality(),
                "knowledge", giver.getKnowledge().stream().map(fact -> "- " + fact).collect(Collectors.joining("\n")),
                "ownedItems", ownedItems(giver),
                "maxGold", String.valueOf(max),
                "maxXp", String.valueOf(max),
                "maxTitle", String.valueOf(QuestValidator.MAX_TITLE_CHARS),
                "maxDescription", String.valueOf(QuestValidator.MAX_DESCRIPTION_CHARS),
                "maxObjectives", String.valueOf(Quest.MAX_OBJECTIVES)));
        String quests = state.getQuestLog().allQuests().stream()
                .map(quest -> quest.getTitle() + " (" + quest.getStatus().name().toLowerCase(Locale.ROOT) + ")")
                .collect(Collectors.joining(", "));
        String user = prompts.fromTemplate("quest.user", Map.of(
                "world", state.getWorld().summarize().strip(),
                "memory", prompts.memoryContext(giver.getId(), PromptBuilder.MAX_MEMORY_CHARS),
                "player", player.getName(),
                "level", String.valueOf(player.getLevel()),
                "location", state.currentLocation().getName() + " [" + state.currentLocation().getId() + "]",
                "quests", quests.isEmpty() ? "none" : quests,
                "name", giver.getName()));
        return new LlmRequest(system, user, true, MAX_TOKENS);
    }

    /** The proposal, or {@code null} if the answer breaks the schema described in the class comment. */
    @Override
    protected QuestProposal parse(LlmResponse response) {
        JsonElement root = parser.parseJson(response.getText(), JsonElement.class);
        if (root == null || !root.isJsonObject() || !onlyFields(root.getAsJsonObject(), QUEST_FIELDS)) {
            return null;
        }
        JsonObject quest = root.getAsJsonObject();
        String title = ActionInterpreter.field(quest, Set.of("title"));
        String description = ActionInterpreter.field(quest, Set.of("description"));
        JsonElement objectivesJson = element(quest, "objectives");
        JsonElement rewardJson = element(quest, "reward");
        if (title == null || description == null || objectivesJson == null || !objectivesJson.isJsonArray()
                || rewardJson == null || !rewardJson.isJsonObject()) {
            return null;
        }
        List<Objective> objectives = new ArrayList<>();
        for (JsonElement objectiveJson : objectivesJson.getAsJsonArray()) {
            Objective objective = objective(objectiveJson);
            if (objective == null) {
                return null;
            }
            objectives.add(objective);
        }
        Reward reward = reward(rewardJson.getAsJsonObject());
        if (reward == null) {
            return null;
        }
        JsonElement giverJson = element(quest, "giver");
        if (giverJson != null && !giverJson.isJsonNull() && !giverJson.isJsonPrimitive()) {
            return null;
        }
        String giver = giverJson == null || giverJson.isJsonNull() || giverJson.getAsString().isBlank()
                ? null : giverJson.getAsString().trim();
        return new QuestProposal(title.strip(), description.strip(), objectives, reward, giver);
    }

    @Override
    protected boolean isValid(QuestProposal output, NPC giver) {
        return questValidator.validate(output, giver).isValid();
    }

    @Override
    protected String describeInvalid(QuestProposal output, NPC giver) {
        String reason = questValidator.validate(output, giver).getReason();
        return reason == null ? super.describeInvalid(output, giver) : reason;
    }

    /** The first valid template quest, trying the templates in turn (see the class comment); {@code null} if none is valid. */
    @Override
    protected QuestProposal fallback(NPC giver) {
        List<QuestProposal> templates = templates(giver);
        return templates.isEmpty() ? null : templates.get(0);
    }

    /**
     * One valid proposal per template that has a valid candidate in the current world, in the order
     * they are tried for this giver: the rotation starts after the giver's previous quests.
     */
    List<QuestProposal> templates(NPC giver) {
        List<ObjectiveType> order = List.of(ObjectiveType.FETCH, ObjectiveType.DEFEAT, ObjectiveType.VISIT);
        int start = (int) (state.getQuestLog().allQuests().stream()
                .filter(quest -> giver.getId().equals(quest.getGiverId())).count() % order.size());
        List<QuestProposal> valid = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            QuestProposal template = template(order.get((start + i) % order.size()), giver);
            if (template != null) {
                valid.add(template);
            }
        }
        return valid;
    }

    /** The first candidate of this template that the validator accepts, or {@code null}. */
    private QuestProposal template(ObjectiveType type, NPC giver) {
        for (QuestProposal candidate : candidates(type, giver)) {
            if (questValidator.validate(candidate, giver).isValid()) {
                return candidate;
            }
        }
        return null;
    }

    private List<QuestProposal> candidates(ObjectiveType type, NPC giver) {
        String name = giver.getName();
        List<QuestProposal> candidates = new ArrayList<>();
        switch (type) {
            case FETCH -> {
                for (Location location : locations()) {
                    for (Item item : location.getItems()) {
                        if (item.getOwnerId() == null && location != state.currentLocation()) {
                            candidates.add(proposal("Find the " + item.getName(),
                                    name + " needs the " + item.getName() + " from the " + location.getName()
                                            + ". Find it and pick it up.",
                                    type, item.getName(), new Reward(FETCH_GOLD, FETCH_XP, null)));
                        }
                    }
                }
            }
            case DEFEAT -> {
                for (Location location : locations()) {
                    for (Enemy enemy : location.getEnemies()) {
                        candidates.add(proposal("Deal with the " + enemy.getName(),
                                name + " wants the " + enemy.getName() + " at the " + location.getName()
                                        + " dealt with before it hurts anyone.",
                                type, enemy.getName(), new Reward(DEFEAT_GOLD, DEFEAT_XP, lightestOwnedItem(giver))));
                    }
                }
            }
            case VISIT -> {
                List<Location> ordered = new ArrayList<>(locations());
                ordered.sort(Comparator.comparing(Location::isVisited));   // stable: unvisited first, world order kept
                for (Location location : ordered) {
                    candidates.add(proposal("Scout the " + location.getName(),
                            name + " asks you to go to the " + location.getName() + " and see it for yourself.",
                            type, location.getId(), new Reward(VISIT_GOLD, VISIT_XP, null)));
                }
            }
        }
        return candidates;
    }

    /** The lightest item the giver owns that anyone could carry, or {@code null}. */
    private String lightestOwnedItem(NPC giver) {
        double limit = state.getPlayer().getInventory().getMaxWeight();
        return locations().stream()
                .flatMap(location -> location.getItems().stream())
                .filter(item -> giver.getId().equals(item.getOwnerId()) && item.getWeight() <= limit)
                .min(Comparator.comparingDouble(Item::getWeight))
                .map(Item::getName)
                .orElse(null);
    }

    private static QuestProposal proposal(String title, String description, ObjectiveType type, String target,
                                          Reward reward) {
        return new QuestProposal(title, description, List.of(new Objective(type, target, 1)), reward, null);
    }

    /**
     * Builds the quest from a valid proposal: a fresh objective per proposed one with the game's name
     * for the target, gold and XP clamped to the level limit, and an id from the giver and the title.
     */
    private Quest toQuest(QuestProposal proposal, NPC giver) {
        List<Objective> objectives = proposal.getObjectives().stream()
                .map(objective -> new Objective(objective.getType(),
                        questValidator.canonicalTarget(objective.getType(), objective.getTargetId()),
                        objective.getRequired()))
                .toList();
        Reward proposed = proposal.getReward();
        int max = maxReward();
        Reward reward = new Reward(Math.min(proposed.getGold(), max), Math.min(proposed.getXp(), max),
                rewardItemName(proposed.getItemName(), giver));
        String id = giver.getId() + ":" + proposal.getTitle().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        return new Quest(id, proposal.getTitle(), proposal.getDescription(), giver.getId(), false, objectives, reward);
    }

    /** The giver's item's exact name for a proposed reward item name, or {@code null}. */
    private String rewardItemName(String proposed, NPC giver) {
        if (proposed == null) {
            return null;
        }
        return locations().stream()
                .flatMap(location -> location.getItems().stream())
                .filter(item -> item.getName().equalsIgnoreCase(proposed.trim()) && giver.getId().equals(item.getOwnerId()))
                .map(Item::getName)
                .findFirst()
                .orElse(proposed.trim());
    }

    private int maxReward() {
        return REWARD_PER_LEVEL * state.getPlayer().getLevel();
    }

    private String ownedItems(NPC giver) {
        String owned = locations().stream()
                .flatMap(location -> location.getItems().stream())
                .filter(item -> giver.getId().equals(item.getOwnerId()))
                .map(Item::describe)
                .collect(Collectors.joining(", "));
        return owned.isEmpty() ? "none" : owned;
    }

    private List<Location> locations() {
        List<Location> locations = new ArrayList<>();
        for (Region region : state.getWorld().getRegions()) {
            locations.addAll(region.getLocations());
        }
        return locations;
    }

    /** {@code {"type":"fetch","target":"bread","count":1}} as an objective, or {@code null}. */
    private static Objective objective(JsonElement json) {
        if (!json.isJsonObject() || !onlyFields(json.getAsJsonObject(), OBJECTIVE_FIELDS)) {
            return null;
        }
        JsonObject object = json.getAsJsonObject();
        String typeName = ActionInterpreter.field(object, Set.of("type"));
        String target = ActionInterpreter.field(object, Set.of("target"));
        JsonElement countJson = element(object, "count");
        Integer count = countJson == null ? Integer.valueOf(1) : wholeNumber(countJson);
        ObjectiveType type = objectiveType(typeName);
        if (type == null || target == null || target.isBlank() || count == null || count < 1) {
            return null;
        }
        return new Objective(type, target.strip(), count);
    }

    /** {@code {"gold":15,"xp":15,"item":null}} as a reward, or {@code null}. */
    private static Reward reward(JsonObject object) {
        if (!onlyFields(object, REWARD_FIELDS)) {
            return null;
        }
        JsonElement goldJson = element(object, "gold");
        JsonElement xpJson = element(object, "xp");
        Integer gold = goldJson == null ? null : wholeNumber(goldJson);
        Integer xp = xpJson == null ? null : wholeNumber(xpJson);
        if (gold == null || xp == null || gold < 0 || xp < 0) {
            return null;
        }
        JsonElement itemJson = element(object, "item");
        String item = null;
        if (itemJson != null && !itemJson.isJsonNull()) {
            if (!itemJson.isJsonPrimitive()) {
                return null;
            }
            String text = itemJson.getAsString().trim();
            item = text.isEmpty() || text.equalsIgnoreCase("none") || text.equalsIgnoreCase("null") ? null : text;
        }
        return new Reward(gold, xp, item);
    }

    /** FETCH, DEFEAT or VISIT for its name in any case; {@code null} for any other type. */
    private static ObjectiveType objectiveType(String name) {
        if (name == null) {
            return null;
        }
        for (ObjectiveType type : ObjectiveType.values()) {
            if (type.name().equalsIgnoreCase(name.trim())) {
                return type;
            }
        }
        return null;
    }

    /** The value as an int if it is a JSON number without a fractional part, else {@code null}. */
    private static Integer wholeNumber(JsonElement value) {
        if (!value.isJsonPrimitive() || !((JsonPrimitive) value).isNumber()) {
            return null;
        }
        double number = value.getAsDouble();
        if (number != Math.rint(number) || Math.abs(number) > Integer.MAX_VALUE) {
            return null;
        }
        return (int) number;
    }

    /** Whether every field's normalized name is one of {@code allowed}. */
    private static boolean onlyFields(JsonObject object, Set<String> allowed) {
        return object.keySet().stream().allMatch(name -> allowed.contains(ActionInterpreter.normalize(name)));
    }

    private static JsonElement element(JsonObject object, String name) {
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (ActionInterpreter.normalize(entry.getKey()).equals(name)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
