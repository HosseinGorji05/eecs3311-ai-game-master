package agent;

import com.google.gson.JsonArray;
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
import java.util.regex.Pattern;
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
 *   <li>{@link #parse} reads only these fields and ignores any other (such as {@code "status"} or
 *       {@code "progress"}): nothing else is read, so a proposal can never set game state. It is tolerant
 *       where that is safe: field names in any case and with {@code _} or {@code -}, a few common names
 *       for the same field (see {@link #TITLE_FIELDS} and the others), the quest wrapped as
 *       {@code {"quest":{...}}} or as a one-element array, a single objective object instead of a list,
 *       whole numbers written as text ({@code "15"}), missing gold or XP counting as 0, and a reward item
 *       of {@code "none"}. It returns {@code null} (an invalid answer, asked again once) for an objective
 *       type other than fetch, defeat or visit, a count that is not a whole number of at least 1, gold
 *       or XP that are not whole non-negative numbers, or a missing title, description, objective list,
 *       target or reward; {@link #describeUnparsable} names the {@link ParseRule} it broke for
 *       {@code LLM_DEBUG}.</li>
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

    /** Field names read for each part (compared ignoring case, {@code _} and {@code -}). */
    static final Set<String> TITLE_FIELDS = Set.of("title", "name", "questtitle", "questname");
    static final Set<String> DESCRIPTION_FIELDS = Set.of("description", "desc", "summary");
    static final Set<String> OBJECTIVES_FIELDS = Set.of("objectives", "objective", "goals", "tasks");
    static final Set<String> REWARD_FIELDS = Set.of("reward", "rewards");
    static final Set<String> GIVER_FIELDS = Set.of("giver", "questgiver", "npc");
    static final Set<String> TYPE_FIELDS = Set.of("type", "kind", "objectivetype");
    static final Set<String> TARGET_FIELDS = Set.of("target", "targetid", "targetname");
    static final Set<String> COUNT_FIELDS = Set.of("count", "quantity", "amount", "required", "number");
    static final Set<String> GOLD_FIELDS = Set.of("gold", "coins");
    static final Set<String> XP_FIELDS = Set.of("xp", "experience", "exp");
    static final Set<String> ITEM_FIELDS = Set.of("item", "itemname", "rewarditem");
    private static final Pattern WHOLE_NUMBER_TEXT = Pattern.compile("\\s*\\d{1,9}\\s*");

    /** The rules of the reply's shape, named in the debug output when a reply breaks one. */
    enum ParseRule {
        /** No JSON object (also not inside a code fence, a {"quest":...} wrapper or a one-element array). */
        JSON,
        TITLE,
        DESCRIPTION,
        /** The objective list is missing, or an objective is not an object. */
        OBJECTIVES,
        OBJECTIVE_TYPE,
        OBJECTIVE_TARGET,
        OBJECTIVE_COUNT,
        /** The reward is missing or not an object. */
        REWARD,
        REWARD_AMOUNT,
        REWARD_ITEM,
        GIVER
    }

    private final QuestValidator questValidator;
    private String parseProblem;

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
        parseProblem = null;
        try {
            return read(parser.parseJson(response.getText(), JsonElement.class));
        } catch (ShapeProblem problem) {
            parseProblem = problem.getMessage();
            return null;
        }
    }

    /** The {@link ParseRule} the last unparsable reply broke, e.g. {@code "OBJECTIVE_TYPE: \"collect\" is not ..."}. */
    @Override
    protected String describeUnparsable(LlmResponse response) {
        return parseProblem == null ? super.describeUnparsable(response) : parseProblem;
    }

    /** A broken {@link ParseRule}, thrown while reading and turned into {@code null} by {@link #parse}. */
    private static final class ShapeProblem extends RuntimeException {
        ShapeProblem(ParseRule rule, String detail) {
            super(rule.name() + ": " + detail, null, false, false);
        }
    }

    private static QuestProposal read(JsonElement root) {
        JsonObject quest = questObject(root);
        String title = text(quest, TITLE_FIELDS, ParseRule.TITLE, "title");
        String description = text(quest, DESCRIPTION_FIELDS, ParseRule.DESCRIPTION, "description");
        JsonElement objectivesJson = element(quest, OBJECTIVES_FIELDS);
        if (objectivesJson == null || objectivesJson.isJsonNull()) {
            throw new ShapeProblem(ParseRule.OBJECTIVES, "there is no objective list");
        }
        JsonArray list;
        if (objectivesJson.isJsonArray()) {
            list = objectivesJson.getAsJsonArray();
        } else if (objectivesJson.isJsonObject()) {
            list = new JsonArray();
            list.add(objectivesJson);
        } else {
            throw new ShapeProblem(ParseRule.OBJECTIVES, "the objectives are not a list of objects");
        }
        List<Objective> objectives = new ArrayList<>();
        for (JsonElement objectiveJson : list) {
            objectives.add(objective(objectiveJson));
        }
        JsonElement rewardJson = element(quest, REWARD_FIELDS);
        if (rewardJson == null || !rewardJson.isJsonObject()) {
            throw new ShapeProblem(ParseRule.REWARD, "the reward is missing or not an object with gold, xp and item");
        }
        Reward reward = reward(rewardJson.getAsJsonObject());
        JsonElement giverJson = element(quest, GIVER_FIELDS);
        if (giverJson != null && !giverJson.isJsonNull() && !giverJson.isJsonPrimitive()) {
            throw new ShapeProblem(ParseRule.GIVER, "the giver is not a name");
        }
        String giver = giverJson == null || giverJson.isJsonNull() || giverJson.getAsString().isBlank()
                ? null : giverJson.getAsString().trim();
        return new QuestProposal(title.strip(), description.strip(), objectives, reward, giver);
    }

    /** The quest object: the reply itself, the one object of a one-element array, or the value of a "quest" field. */
    private static JsonObject questObject(JsonElement root) {
        if (root != null && root.isJsonArray() && root.getAsJsonArray().size() == 1) {
            root = root.getAsJsonArray().get(0);
        }
        if (root == null || !root.isJsonObject()) {
            throw new ShapeProblem(ParseRule.JSON, "the reply holds no JSON object");
        }
        JsonObject object = root.getAsJsonObject();
        JsonElement wrapped = element(object, Set.of("quest"));
        if (element(object, TITLE_FIELDS) == null && wrapped != null && wrapped.isJsonObject()) {
            return wrapped.getAsJsonObject();
        }
        return object;
    }

    private static String text(JsonObject object, Set<String> names, ParseRule rule, String what) {
        JsonElement value = element(object, names);
        if (value == null || !value.isJsonPrimitive()) {
            throw new ShapeProblem(rule, "the " + what + " is missing or not text");
        }
        return value.getAsString();
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
        String wanted = QuestValidator.normalizeName(proposed);
        return locations().stream()
                .flatMap(location -> location.getItems().stream())
                .filter(item -> item.getName().equalsIgnoreCase(wanted) && giver.getId().equals(item.getOwnerId()))
                .map(Item::getName)
                .findFirst()
                .orElse(wanted);
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

    /**
     * {@code {"type":"fetch","target":"bread","count":1}} as an objective. The target may also be named
     * after its kind ({@code "item"} for fetch, {@code "enemy"} for defeat, {@code "location"} for visit);
     * a missing count is 1.
     */
    private static Objective objective(JsonElement json) {
        if (!json.isJsonObject()) {
            throw new ShapeProblem(ParseRule.OBJECTIVES, "an objective is not an object");
        }
        JsonObject object = json.getAsJsonObject();
        JsonElement typeJson = element(object, TYPE_FIELDS);
        String typeName = typeJson != null && typeJson.isJsonPrimitive() ? typeJson.getAsString() : null;
        ObjectiveType type = objectiveType(typeName);
        if (type == null) {
            throw new ShapeProblem(ParseRule.OBJECTIVE_TYPE, (typeName == null ? "the type is missing"
                    : "\"" + typeName + "\" is not") + " fetch, defeat or visit");
        }
        JsonElement targetJson = element(object, TARGET_FIELDS);
        if (targetJson == null) {
            targetJson = element(object, Set.of(switch (type) {
                case FETCH -> "item";
                case DEFEAT -> "enemy";
                case VISIT -> "location";
            }));
        }
        if (targetJson == null || !targetJson.isJsonPrimitive() || targetJson.getAsString().isBlank()) {
            throw new ShapeProblem(ParseRule.OBJECTIVE_TARGET, typeName.trim().toLowerCase(Locale.ROOT)
                    + " objective has no target name");
        }
        JsonElement countJson = element(object, COUNT_FIELDS);
        Integer count = countJson == null || countJson.isJsonNull() ? Integer.valueOf(1) : wholeNumber(countJson);
        if (count == null || count < 1) {
            throw new ShapeProblem(ParseRule.OBJECTIVE_COUNT, "count " + countJson + " is not a whole number of at least 1");
        }
        return new Objective(type, targetJson.getAsString().strip(), count);
    }

    /** {@code {"gold":15,"xp":15,"item":null}} as a reward; missing gold or XP is 0. */
    private static Reward reward(JsonObject object) {
        int gold = amount(object, GOLD_FIELDS, "gold");
        int xp = amount(object, XP_FIELDS, "xp");
        JsonElement itemJson = element(object, ITEM_FIELDS);
        String item = null;
        if (itemJson != null && !itemJson.isJsonNull()) {
            if (!itemJson.isJsonPrimitive()) {
                throw new ShapeProblem(ParseRule.REWARD_ITEM, "the reward item is not a name");
            }
            String text = itemJson.getAsString().trim();
            item = text.isEmpty() || text.equalsIgnoreCase("none") || text.equalsIgnoreCase("null") ? null : text;
        }
        return new Reward(gold, xp, item);
    }

    private static int amount(JsonObject object, Set<String> names, String what) {
        JsonElement value = element(object, names);
        if (value == null || value.isJsonNull()) {
            return 0;
        }
        Integer amount = wholeNumber(value);
        if (amount == null || amount < 0) {
            throw new ShapeProblem(ParseRule.REWARD_AMOUNT, what + " " + value + " is not a whole number of at least 0");
        }
        return amount;
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

    /**
     * The value as an int if it is a JSON number without a fractional part, or text holding only digits
     * ({@code "15"}); else {@code null}.
     */
    private static Integer wholeNumber(JsonElement value) {
        if (!value.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive primitive = (JsonPrimitive) value;
        if (primitive.isString()) {
            return WHOLE_NUMBER_TEXT.matcher(primitive.getAsString()).matches()
                    ? Integer.valueOf(primitive.getAsString().trim()) : null;
        }
        if (!primitive.isNumber()) {
            return null;
        }
        double number = value.getAsDouble();
        if (number != Math.rint(number) || Math.abs(number) > Integer.MAX_VALUE) {
            return null;
        }
        return (int) number;
    }

    /** The value of the first field whose normalized name is one of {@code names}, or {@code null}. */
    private static JsonElement element(JsonObject object, Set<String> names) {
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (names.contains(ActionInterpreter.normalize(entry.getKey()))) {
                return entry.getValue();
            }
        }
        return null;
    }
}
