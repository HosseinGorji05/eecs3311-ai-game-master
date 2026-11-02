package agent;

import engine.character.Enemy;
import engine.commands.ValidationResult;
import engine.core.DialogueMode;
import engine.core.GameState;
import engine.items.Container;
import engine.items.Item;
import engine.items.KeyItem;
import engine.quests.Objective;
import engine.quests.ObjectiveType;
import engine.quests.Quest;
import engine.quests.QuestLog;
import engine.quests.QuestStatus;
import engine.world.Exit;
import engine.world.Location;
import engine.world.NPC;
import engine.world.Region;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Checks a {@link QuestProposal} against the real game before it may be offered (F07, SD07): the
 * engine decides, the model only proposes.
 *
 * <p>The rules, checked in this order; the first one broken is named at the start of the reason
 * ({@code "TARGET_REACHABLE: ..."}):
 * <ol>
 *   <li>{@link Rule#GIVER}: the giver is the NPC the player is talking to, gives quests, and is the
 *       giver the proposal names (if it names one).</li>
 *   <li>{@link Rule#OPEN_QUEST}: the player has no active quest from this giver.</li>
 *   <li>{@link Rule#QUEST_LOG_FULL}: fewer than three side quests are active.</li>
 *   <li>{@link Rule#TITLE} and {@link Rule#DESCRIPTION}: not blank, at most
 *       {@value #MAX_TITLE_CHARS} and {@value #MAX_DESCRIPTION_CHARS} characters.</li>
 *   <li>{@link Rule#OBJECTIVE_COUNT}: 1-3 objectives.</li>
 *   <li>{@link Rule#TARGET_EXISTS}: a FETCH item lies in some location, a DEFEAT enemy is alive
 *       somewhere, a VISIT location exists (by id or name).</li>
 *   <li>{@link Rule#TARGET_AVAILABLE}: a FETCH item belongs to nobody (taking an NPC's item is a
 *       theft), and a VISIT location is not where the player already is.</li>
 *   <li>{@link Rule#TARGET_REACHABLE}: the target's location can be reached from the player's
 *       location; a locked exit counts as passable only if its key is carried or lies in a reachable
 *       location (also inside a container).</li>
 *   <li>{@link Rule#TARGET_COUNT}: the required counts per target add up to no more than exist
 *       (reachable unowned items of that name, living enemies of that name, 1 for a location).</li>
 *   <li>{@link Rule#REWARD_ITEM}: a reward item lies in some location, belongs to the giver and
 *       weighs no more than the carrying limit.</li>
 *   <li>{@link Rule#DUPLICATE}: no active or completed quest has the same title or the same
 *       objectives.</li>
 * </ol>
 *
 * <p>The objective types themselves (FETCH, DEFEAT, VISIT only), whole non-negative numbers and the
 * absence of unknown fields are checked earlier, when {@code QuestGenerator} reads the JSON, because
 * a proposal holds real {@link Objective}s and {@link engine.quests.Reward}s. Reward amounts are not
 * a rule here: amounts above the level limit are clamped by {@code QuestGenerator} (UC07 3b).
 *
 * <p>Deviation from View 6: the validator holds the {@link GameState} instead of the {@code World},
 * because the world exists only once a game starts, and reachability needs the player's location and
 * keys.
 */
public class QuestValidator {

    /** Longest title an offer may have. */
    public static final int MAX_TITLE_CHARS = 60;
    /** Longest description an offer may have. */
    public static final int MAX_DESCRIPTION_CHARS = 300;

    /** The validation rules, in the order they are checked. */
    public enum Rule {
        GIVER,
        OPEN_QUEST,
        QUEST_LOG_FULL,
        TITLE,
        DESCRIPTION,
        OBJECTIVE_COUNT,
        TARGET_EXISTS,
        TARGET_AVAILABLE,
        TARGET_REACHABLE,
        TARGET_COUNT,
        REWARD_ITEM,
        DUPLICATE
    }

    private final GameState state;

    public QuestValidator(GameState state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    /**
     * Validates a proposal from the NPC the player is talking to.
     *
     * @return a valid result, or the first broken rule (GIVER if the player is not in a conversation)
     */
    public ValidationResult validate(QuestProposal proposal) {
        if (!(state.getMode() instanceof DialogueMode dialogue)) {
            return fail(Rule.GIVER, "the player is not talking to anyone");
        }
        return validate(proposal, dialogue.getNpc());
    }

    /** Validates a proposal for a quest from {@code giver}; see the class comment for the rules. */
    public ValidationResult validate(QuestProposal proposal, NPC giver) {
        Objects.requireNonNull(proposal, "proposal");
        Objects.requireNonNull(giver, "giver");
        QuestLog log = state.getQuestLog();
        if (!(state.getMode() instanceof DialogueMode dialogue) || dialogue.getNpc() != giver) {
            return fail(Rule.GIVER, giver.getName() + " is not the person the player is talking to");
        }
        if (!giver.isQuestGiver()) {
            return fail(Rule.GIVER, giver.getName() + " does not give quests");
        }
        if (proposal.getGiverName() != null && !giver.isCalled(proposal.getGiverName())) {
            return fail(Rule.GIVER, "the proposal names " + proposal.getGiverName() + " as the giver, not "
                    + giver.getName());
        }
        if (log.hasQuestFrom(giver)) {
            return fail(Rule.OPEN_QUEST, "the player already has an active quest from " + giver.getName());
        }
        if (!log.canOfferFrom(giver)) {
            return fail(Rule.QUEST_LOG_FULL, "the player already has " + log.activeSideQuestCount() + " side quests");
        }
        ValidationResult text = checkText(proposal.getTitle(), Rule.TITLE, MAX_TITLE_CHARS);
        if (text == null) {
            text = checkText(proposal.getDescription(), Rule.DESCRIPTION, MAX_DESCRIPTION_CHARS);
        }
        if (text != null) {
            return text;
        }
        List<Objective> objectives = proposal.getObjectives();
        if (objectives.isEmpty() || objectives.size() > Quest.MAX_OBJECTIVES) {
            return fail(Rule.OBJECTIVE_COUNT, "a quest needs 1-" + Quest.MAX_OBJECTIVES + " objectives, not "
                    + objectives.size());
        }
        Set<Location> reachable = reachableLocations();
        Map<String, Integer> needed = new HashMap<>();
        Map<String, Integer> available = new HashMap<>();
        for (Objective objective : objectives) {
            Target target = new Target();
            ValidationResult problem = checkTarget(objective, reachable, target);
            if (problem != null) {
                return problem;
            }
            String key = key(objective.getType(), target.canonical);
            needed.merge(key, objective.getRequired(), Integer::sum);
            available.put(key, target.count);
        }
        for (Map.Entry<String, Integer> entry : needed.entrySet()) {
            if (entry.getValue() > available.get(entry.getKey())) {
                return fail(Rule.TARGET_COUNT, entry.getKey() + " is needed " + entry.getValue()
                        + " times, but only " + available.get(entry.getKey()) + " can be done");
            }
        }
        ValidationResult rewardItem = checkRewardItem(proposal.getReward().getItemName(), giver);
        if (rewardItem != null) {
            return rewardItem;
        }
        Set<String> objectiveKeys = objectives.stream()
                .map(objective -> key(objective.getType(), canonicalTarget(objective.getType(), objective.getTargetId())))
                .collect(Collectors.toSet());
        for (Quest quest : log.allQuests()) {
            if (quest.getStatus() == QuestStatus.FAILED) {
                continue;
            }
            if (quest.getTitle().trim().equalsIgnoreCase(proposal.getTitle().trim())) {
                return fail(Rule.DUPLICATE, "the player already has a quest called " + quest.getTitle());
            }
            if (objectiveKeys.equals(objectiveKeys(quest))) {
                return fail(Rule.DUPLICATE, "the quest " + quest.getTitle() + " has the same objectives");
            }
        }
        return new ValidationResult(true, null);
    }

    /**
     * The name the game uses for an objective's target: the item's or enemy's exact name, or the
     * location's id for VISIT (the model may name the location); {@code null} if there is no such thing.
     */
    String canonicalTarget(ObjectiveType type, String target) {
        String wanted = target.trim();
        for (Location location : locations()) {
            switch (type) {
                case FETCH -> {
                    for (Item item : location.getItems()) {
                        if (item.getName().equalsIgnoreCase(wanted)) {
                            return item.getName();
                        }
                    }
                }
                case DEFEAT -> {
                    Enemy enemy = location.findEnemy(wanted);
                    if (enemy != null && enemy.isAlive()) {
                        return enemy.getName();
                    }
                }
                case VISIT -> {
                    if (location.getId().equalsIgnoreCase(wanted) || location.getName().equalsIgnoreCase(wanted)) {
                        return location.getId();
                    }
                }
            }
        }
        return null;
    }

    /** What {@link #checkTarget} found out about a valid target. */
    private static final class Target {
        String canonical;
        int count;
    }

    private ValidationResult checkTarget(Objective objective, Set<Location> reachable, Target target) {
        String wanted = objective.getTargetId();
        String canonical = canonicalTarget(objective.getType(), wanted);
        if (canonical == null) {
            String carried = objective.getType() == ObjectiveType.FETCH
                    && state.getPlayer().getInventory().find(wanted) != null ? " (the player already carries it)" : "";
            return fail(Rule.TARGET_EXISTS, "there is no " + typeName(objective.getType()) + " " + wanted
                    + " in the world" + carried);
        }
        target.canonical = canonical;
        switch (objective.getType()) {
            case FETCH -> {
                List<Item> unowned = new ArrayList<>();
                List<Item> reachableUnowned = new ArrayList<>();
                String owner = null;
                for (Location location : locations()) {
                    for (Item item : location.getItems()) {
                        if (!item.getName().equalsIgnoreCase(canonical)) {
                            continue;
                        }
                        if (item.getOwnerId() != null) {
                            owner = item.getOwnerId();
                            continue;
                        }
                        unowned.add(item);
                        if (reachable.contains(location)) {
                            reachableUnowned.add(item);
                        }
                    }
                }
                if (unowned.isEmpty()) {
                    NPC npc = state.getWorld().findNpc(owner);
                    return fail(Rule.TARGET_AVAILABLE, "the " + canonical + " belongs to "
                            + (npc == null ? owner : npc.getName()) + ", so taking it would be a theft");
                }
                if (reachableUnowned.isEmpty()) {
                    return fail(Rule.TARGET_REACHABLE, "no " + canonical + " can be reached from "
                            + state.currentLocation().getName());
                }
                target.count = reachableUnowned.size();
            }
            case DEFEAT -> {
                int total = 0;
                for (Location location : locations()) {
                    if (reachable.contains(location)) {
                        total += (int) location.getEnemies().stream()
                                .filter(enemy -> enemy.isAlive() && enemy.getName().equalsIgnoreCase(canonical))
                                .count();
                    }
                }
                if (total == 0) {
                    return fail(Rule.TARGET_REACHABLE, "the " + canonical + " cannot be reached from "
                            + state.currentLocation().getName());
                }
                target.count = total;
            }
            case VISIT -> {
                Location location = state.getWorld().findLocation(canonical);
                if (location == state.currentLocation()) {
                    return fail(Rule.TARGET_AVAILABLE, "the player is already at the " + location.getName());
                }
                if (!reachable.contains(location)) {
                    return fail(Rule.TARGET_REACHABLE, "the " + location.getName() + " cannot be reached from "
                            + state.currentLocation().getName());
                }
                target.count = 1;
            }
        }
        return null;
    }

    private ValidationResult checkRewardItem(String itemName, NPC giver) {
        if (itemName == null) {
            return null;
        }
        for (Location location : locations()) {
            for (Item item : location.getItems()) {
                if (item.getName().equalsIgnoreCase(itemName.trim()) && giver.getId().equals(item.getOwnerId())) {
                    double limit = state.getPlayer().getInventory().getMaxWeight();
                    if (item.getWeight() > limit) {
                        return fail(Rule.REWARD_ITEM, "the " + item.getName() + " weighs more than the "
                                + limit + " kg anyone can carry");
                    }
                    return null;
                }
            }
        }
        return fail(Rule.REWARD_ITEM, giver.getName() + " owns no " + itemName + " to give");
    }

    /**
     * The locations the player can reach from where they are. A locked exit is passable once its key
     * is carried or lies (possibly in a container) in a location reached so far; this repeats until
     * nothing new is reached.
     */
    private Set<Location> reachableLocations() {
        Set<String> keys = new HashSet<>();
        collectKeys(state.getPlayer().getInventory().getItems(), keys);
        Set<Location> reached = new LinkedHashSet<>();
        reached.add(state.currentLocation());
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Location location : List.copyOf(reached)) {
                collectKeys(location.getItems(), keys);
            }
            for (Location location : List.copyOf(reached)) {
                for (Exit exit : location.getExits()) {
                    boolean passable = !exit.isLocked()
                            || keys.contains(exit.getRequiredKeyName().toLowerCase(Locale.ROOT));
                    if (passable && reached.add(exit.getDestination())) {
                        grew = true;
                    }
                }
            }
        }
        return reached;
    }

    private static void collectKeys(List<Item> items, Set<String> keys) {
        for (Item item : items) {
            if (item instanceof KeyItem) {
                keys.add(item.getName().toLowerCase(Locale.ROOT));
            }
            if (item instanceof Container container) {
                collectKeys(container.getContents(), keys);
            }
        }
    }

    private List<Location> locations() {
        List<Location> locations = new ArrayList<>();
        for (Region region : state.getWorld().getRegions()) {
            locations.addAll(region.getLocations());
        }
        return locations;
    }

    private static ValidationResult checkText(String text, Rule rule, int maxChars) {
        if (text.isBlank()) {
            return fail(rule, "it is blank");
        }
        if (text.strip().length() > maxChars) {
            return fail(rule, "it is longer than " + maxChars + " characters");
        }
        return null;
    }

    private static Set<String> objectiveKeys(Quest quest) {
        return quest.getObjectives().stream()
                .map(objective -> key(objective.getType(), objective.getTargetId()))
                .collect(Collectors.toSet());
    }

    private static String key(ObjectiveType type, String target) {
        return typeName(type) + " " + (target == null ? "?" : target.toLowerCase(Locale.ROOT));
    }

    private static String typeName(ObjectiveType type) {
        return type.name().toLowerCase(Locale.ROOT);
    }

    private static ValidationResult fail(Rule rule, String detail) {
        return new ValidationResult(false, rule.name() + ": " + detail);
    }
}
