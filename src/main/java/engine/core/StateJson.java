package engine.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import engine.character.CharacterClass;
import engine.character.Enemy;
import engine.character.Player;
import engine.character.StatType;
import engine.character.Stats;
import engine.events.EventType;
import engine.events.EventVerb;
import engine.events.GameEvent;
import engine.items.Consumable;
import engine.items.Container;
import engine.items.Item;
import engine.items.KeyItem;
import engine.items.Weapon;
import engine.quests.Objective;
import engine.quests.ObjectiveType;
import engine.quests.Quest;
import engine.quests.QuestLog;
import engine.quests.QuestStatus;
import engine.quests.Reward;
import engine.world.Direction;
import engine.world.Exit;
import engine.world.Location;
import engine.world.NPC;
import engine.world.Region;
import engine.world.World;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Writes {@link GameState}'s full state as JSON and reads it back (the inside of a
 * {@code GameSnapshot}); only {@code GameState} uses it.
 *
 * <p>The state holds the turn, the current location's id, the mode (exploration, or dialogue with an
 * NPC's id and whether a quest was offered), the player (class, stats, HP, gold, XP, backstory, the
 * inventory as a tree of items with every container's contents, and the index of the equipped weapon
 * among the carried items), the whole world (regions, locations with their visited flag, items,
 * people, living enemies with their HP, and exits with their key and whether they are locked), the
 * quest log with each objective's progress and each quest's status, the event log, and the story
 * memory. Fields are always written in the same order, so the same state always gives the same text.
 *
 * <p>{@link #read} builds every object first and checks it with the domain's own rules (stat points,
 * carrying limit, container capacity, quest limits, ...), so a damaged file is found before anything
 * in the running game changes.
 */
final class StateJson {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    /** Everything a restore installs, already built and checked. */
    record Restored(int turn, Player player, World world, Location currentLocation, GameMode mode, List<Quest> quests,
                    List<GameEvent> events, List<String> facts, List<String> summaries, List<GameEvent> recent) {
    }

    private StateJson() {
    }

    // ---------------------------------------------------------------- writing

    static String write(GameState state) {
        JsonObject root = new JsonObject();
        root.addProperty("turn", state.getTurn());
        root.addProperty("currentLocation", state.currentLocation().getId());
        root.add("mode", mode(state.getMode()));
        root.add("player", player(state.getPlayer()));
        root.add("world", world(state.getWorld()));
        root.add("quests", array(state.getQuestLog().allQuests(), StateJson::quest));
        root.add("events", array(state.getEventLog().recent(Integer.MAX_VALUE), StateJson::event));
        StoryMemory memory = state.getStoryMemory();
        JsonObject memoryJson = new JsonObject();
        memoryJson.add("facts", strings(memory.getFixedFacts()));
        memoryJson.add("summaries", strings(memory.getSummaries()));
        memoryJson.add("recent", array(memory.getRecentEvents(), StateJson::event));
        root.add("memory", memoryJson);
        return GSON.toJson(root);
    }

    private static JsonObject mode(GameMode mode) {
        JsonObject json = new JsonObject();
        if (mode instanceof DialogueMode dialogue) {
            json.addProperty("kind", "dialogue");
            json.addProperty("npc", dialogue.getNpc().getId());
            json.addProperty("questOffered", dialogue.isQuestOffered());
        } else if (mode instanceof ExplorationMode) {
            json.addProperty("kind", "exploration");
        } else {
            throw new IllegalStateException("Cannot save in " + mode.getClass().getSimpleName());
        }
        return json;
    }

    private static JsonObject player(Player player) {
        JsonObject json = new JsonObject();
        json.addProperty("name", player.getName());
        json.addProperty("class", player.getCharacterClass().name());
        json.addProperty("strength", player.getStats().modifier(StatType.STRENGTH));
        json.addProperty("agility", player.getStats().modifier(StatType.AGILITY));
        json.addProperty("intelligence", player.getStats().modifier(StatType.INTELLIGENCE));
        json.addProperty("maxHp", player.getMaxHp());
        json.addProperty("hp", player.getHp());
        json.addProperty("gold", player.getGold());
        json.addProperty("xp", player.getXp());
        json.addProperty("backstory", player.getBackstory());
        List<Item> carried = player.getInventory().getItems();
        json.add("inventory", array(carried, StateJson::item));
        int equipped = -1;
        for (int i = 0; i < carried.size(); i++) {
            if (carried.get(i) == player.getEquippedWeapon()) {
                equipped = i;
            }
        }
        json.addProperty("equipped", equipped < 0 ? null : equipped);
        return json;
    }

    private static JsonObject item(Item item) {
        JsonObject json = new JsonObject();
        json.addProperty("kind", item instanceof Weapon ? "weapon" : item instanceof Consumable ? "consumable"
                : item instanceof KeyItem ? "key" : item instanceof Container ? "container" : null);
        json.addProperty("name", item.getName());
        json.addProperty("weight", item instanceof Container container ? container.getOwnWeight() : item.getWeight());
        json.addProperty("owner", item.getOwnerId());
        if (item instanceof Weapon weapon) {
            json.addProperty("damage", weapon.getDamage());
        } else if (item instanceof Consumable consumable) {
            json.addProperty("heal", consumable.getHealAmount());
        } else if (item instanceof Container container) {
            json.addProperty("capacity", container.getCapacity());
            json.add("contents", array(container.getContents(), StateJson::item));
        } else if (!(item instanceof KeyItem)) {
            throw new IllegalStateException("Cannot save an item of type " + item.getClass().getSimpleName());
        }
        return json;
    }

    private static JsonObject world(World world) {
        JsonObject json = new JsonObject();
        json.addProperty("start", world.getStartLocation().getId());
        json.add("regions", array(world.getRegions(), region -> {
            JsonObject regionJson = new JsonObject();
            regionJson.addProperty("name", region.getName());
            regionJson.add("locations", array(region.getLocations(), StateJson::location));
            return regionJson;
        }));
        return json;
    }

    private static JsonObject location(Location location) {
        JsonObject json = new JsonObject();
        json.addProperty("id", location.getId());
        json.addProperty("name", location.getName());
        json.addProperty("description", location.getDescription());
        json.addProperty("visited", location.isVisited());
        json.add("items", array(location.getItems(), StateJson::item));
        json.add("npcs", array(location.getNpcs(), npc -> {
            JsonObject npcJson = new JsonObject();
            npcJson.addProperty("id", npc.getId());
            npcJson.addProperty("name", npc.getName());
            npcJson.addProperty("role", npc.getRole());
            npcJson.addProperty("personality", npc.getPersonality());
            npcJson.add("knowledge", strings(npc.getKnowledge()));
            npcJson.add("secrets", strings(npc.getSecrets()));
            npcJson.addProperty("questGiver", npc.isQuestGiver());
            return npcJson;
        }));
        json.add("enemies", array(location.getEnemies(), enemy -> {
            JsonObject enemyJson = new JsonObject();
            enemyJson.addProperty("name", enemy.getName());
            enemyJson.addProperty("maxHp", enemy.getMaxHp());
            enemyJson.addProperty("hp", enemy.getHp());
            enemyJson.addProperty("attack", enemy.attackBonus());
            enemyJson.addProperty("defense", enemy.defense());
            return enemyJson;
        }));
        json.add("exits", array(location.getExits(), exit -> {
            JsonObject exitJson = new JsonObject();
            exitJson.addProperty("id", exit.getId());
            exitJson.addProperty("direction", exit.getDirection().name());
            exitJson.addProperty("to", exit.getDestination().getId());
            exitJson.addProperty("key", exit.getRequiredKeyName());
            exitJson.addProperty("locked", exit.isLocked());
            return exitJson;
        }));
        return json;
    }

    private static JsonObject quest(Quest quest) {
        JsonObject json = new JsonObject();
        json.addProperty("id", quest.getId());
        json.addProperty("title", quest.getTitle());
        json.addProperty("description", quest.getDescription());
        json.addProperty("giver", quest.getGiverId());
        json.addProperty("main", quest.isMain());
        json.addProperty("status", quest.getStatus().name());
        json.add("objectives", array(quest.getObjectives(), objective -> {
            JsonObject objectiveJson = new JsonObject();
            objectiveJson.addProperty("type", objective.getType().name());
            objectiveJson.addProperty("target", objective.getTargetId());
            objectiveJson.addProperty("required", objective.getRequired());
            objectiveJson.addProperty("progress", objective.getProgress());
            return objectiveJson;
        }));
        JsonObject reward = new JsonObject();
        reward.addProperty("gold", quest.getReward().getGold());
        reward.addProperty("xp", quest.getReward().getXp());
        reward.addProperty("item", quest.getReward().getItemName());
        json.add("reward", reward);
        return json;
    }

    private static JsonObject event(GameEvent event) {
        JsonObject json = new JsonObject();
        json.addProperty("type", event.getType().name());
        json.addProperty("turn", event.getTurn());
        json.addProperty("timestamp", event.getTimestamp().toString());
        json.addProperty("description", event.getDescription());
        json.addProperty("subject", event.getSubjectId());
        json.addProperty("delta", event.getAlignmentDelta());
        json.addProperty("verb", event.getVerb() == null ? null : event.getVerb().name());
        return json;
    }

    private static <T> JsonArray array(List<T> values, Function<T, JsonElement> toJson) {
        JsonArray array = new JsonArray();
        values.forEach(value -> array.add(toJson.apply(value)));
        return array;
    }

    private static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    // ---------------------------------------------------------------- reading

    /**
     * Builds the saved state without touching any running game.
     *
     * @throws IllegalArgumentException if the JSON is damaged or describes an impossible game
     */
    static Restored read(String stateJson) {
        try {
            JsonObject root = JsonParser.parseString(stateJson).getAsJsonObject();
            int turn = integer(root, "turn");
            check(turn >= 1, "the turn must be at least 1");
            World world = readWorld(object(root, "world"));
            Location current = world.findLocation(text(root, "currentLocation"));
            check(current != null, "the current location is not in the world");
            GameMode mode = readMode(object(root, "mode"), current);
            Player player = readPlayer(object(root, "player"));
            List<Quest> quests = list(root, "quests", StateJson::readQuest);
            new QuestLog().restore(quests);   // checks the quest log's rules before the real one changes
            List<GameEvent> events = list(root, "events", StateJson::readEvent);
            JsonObject memory = object(root, "memory");
            return new Restored(turn, player, world, current, mode, quests, events,
                    list(memory, "facts", JsonElement::getAsString), list(memory, "summaries", JsonElement::getAsString),
                    list(memory, "recent", StateJson::readEvent));
        } catch (RuntimeException damaged) {
            throw new IllegalArgumentException("The saved game is damaged (" + damaged.getMessage() + ").", damaged);
        }
    }

    private static GameMode readMode(JsonObject json, Location current) {
        String kind = text(json, "kind");
        if (kind.equals("exploration")) {
            return new ExplorationMode();
        }
        check(kind.equals("dialogue"), "unknown mode " + kind);
        String npcId = text(json, "npc");
        NPC npc = current.getNpcs().stream().filter(each -> each.getId().equals(npcId)).findFirst().orElse(null);
        check(npc != null, "the person in the conversation is not here");
        DialogueMode dialogue = new DialogueMode(npc);
        if (bool(json, "questOffered")) {
            dialogue.setQuestOffered();
        }
        return dialogue;
    }

    private static Player readPlayer(JsonObject json) {
        Stats stats = new Stats(integer(json, "strength"), integer(json, "agility"), integer(json, "intelligence"));
        int maxHp = integer(json, "maxHp");
        int hp = integer(json, "hp");
        check(hp >= 1 && hp <= maxHp, "the player's HP must be between 1 and " + maxHp);
        List<Item> carried = list(json, "inventory", StateJson::readItem);
        Player player = new Player(text(json, "name"), CharacterClass.valueOf(text(json, "class")), stats, maxHp, carried);
        player.takeDamage(maxHp - hp);
        player.addReward(new Reward(integer(json, "gold"), integer(json, "xp"), null));
        String backstory = optionalText(json, "backstory");
        if (backstory != null) {
            player.setBackstory(backstory);
        }
        JsonElement equipped = json.get("equipped");
        if (equipped != null && !equipped.isJsonNull()) {
            int index = equipped.getAsInt();
            check(index >= 0 && index < carried.size() && carried.get(index) instanceof Weapon,
                    "the equipped weapon is not carried");
            player.equip((Weapon) carried.get(index));
        }
        return player;
    }

    private static Item readItem(JsonElement element) {
        JsonObject json = element.getAsJsonObject();
        String name = text(json, "name");
        double weight = json.get("weight").getAsDouble();
        String owner = optionalText(json, "owner");
        String kind = text(json, "kind");
        switch (kind) {
            case "weapon":
                return new Weapon(name, weight, integer(json, "damage"), owner);
            case "consumable":
                return new Consumable(name, weight, integer(json, "heal"), owner);
            case "key":
                return new KeyItem(name, weight, owner);
            case "container":
                Container container = new Container(name, weight, integer(json, "capacity"), owner);
                for (Item content : list(json, "contents", StateJson::readItem)) {
                    check(container.add(content), "the " + name + " cannot hold the " + content.getName());
                }
                return container;
            default:
                throw new IllegalArgumentException("unknown item kind " + kind);
        }
    }

    private static World readWorld(JsonObject json) {
        Map<String, Location> locations = new HashMap<>();
        Map<Location, JsonArray> exits = new HashMap<>();
        List<Region> regions = new ArrayList<>();
        for (JsonElement regionElement : array(json, "regions")) {
            JsonObject regionJson = regionElement.getAsJsonObject();
            List<Location> regionLocations = new ArrayList<>();
            for (JsonElement locationElement : array(regionJson, "locations")) {
                JsonObject locationJson = locationElement.getAsJsonObject();
                Location location = new Location(text(locationJson, "id"), text(locationJson, "name"),
                        text(locationJson, "description"));
                if (bool(locationJson, "visited")) {
                    location.markVisited();
                }
                list(locationJson, "items", StateJson::readItem).forEach(location::addItem);
                list(locationJson, "npcs", StateJson::readNpc).forEach(location::addNpc);
                list(locationJson, "enemies", StateJson::readEnemy).forEach(location::addEnemy);
                check(locations.put(location.getId(), location) == null, "two locations are called " + location.getId());
                exits.put(location, array(locationJson, "exits"));
                regionLocations.add(location);
            }
            regions.add(new Region(text(regionJson, "name"), regionLocations));
        }
        for (Map.Entry<Location, JsonArray> entry : exits.entrySet()) {
            for (JsonElement exitElement : entry.getValue()) {
                JsonObject exitJson = exitElement.getAsJsonObject();
                Location destination = locations.get(text(exitJson, "to"));
                check(destination != null, "an exit leads to an unknown location");
                String key = optionalText(exitJson, "key");
                Exit exit = new Exit(text(exitJson, "id"), Direction.valueOf(text(exitJson, "direction")), destination, key);
                if (!bool(exitJson, "locked")) {
                    check(exit.unlock(key == null ? null : new KeyItem(key, 0)), "an exit is open without its key");
                } else {
                    check(key != null, "a locked exit has no key");
                }
                entry.getKey().addExit(exit);
            }
        }
        Location start = locations.get(text(json, "start"));
        check(start != null, "the start location is not in the world");
        return new World(regions, start);
    }

    private static NPC readNpc(JsonElement element) {
        JsonObject json = element.getAsJsonObject();
        return new NPC(text(json, "id"), text(json, "name"), text(json, "role"), text(json, "personality"),
                list(json, "knowledge", JsonElement::getAsString), list(json, "secrets", JsonElement::getAsString),
                bool(json, "questGiver"));
    }

    private static Enemy readEnemy(JsonElement element) {
        JsonObject json = element.getAsJsonObject();
        int maxHp = integer(json, "maxHp");
        int hp = integer(json, "hp");
        check(hp >= 1 && hp <= maxHp, "an enemy's HP must be between 1 and its maximum");
        Enemy enemy = new Enemy(text(json, "name"), maxHp, integer(json, "attack"), integer(json, "defense"));
        enemy.takeDamage(maxHp - hp);
        return enemy;
    }

    private static Quest readQuest(JsonElement element) {
        JsonObject json = element.getAsJsonObject();
        List<Objective> objectives = list(json, "objectives", objectiveElement -> {
            JsonObject objectiveJson = objectiveElement.getAsJsonObject();
            Objective objective = new Objective(ObjectiveType.valueOf(text(objectiveJson, "type")),
                    text(objectiveJson, "target"), integer(objectiveJson, "required"));
            int progress = integer(objectiveJson, "progress");
            check(progress >= 0 && progress <= objective.getRequired(), "an objective's progress is out of range");
            for (int i = 0; i < progress; i++) {
                objective.advance();
            }
            return objective;
        });
        JsonObject rewardJson = object(json, "reward");
        Quest quest = new Quest(text(json, "id"), text(json, "title"), text(json, "description"),
                optionalText(json, "giver"), bool(json, "main"), objectives,
                new Reward(integer(rewardJson, "gold"), integer(rewardJson, "xp"), optionalText(rewardJson, "item")));
        switch (QuestStatus.valueOf(text(json, "status"))) {
            case COMPLETED -> quest.complete();
            case FAILED -> quest.fail();
            case ACTIVE -> { }
        }
        return quest;
    }

    private static GameEvent readEvent(JsonElement element) {
        JsonObject json = element.getAsJsonObject();
        String verb = optionalText(json, "verb");
        return new GameEvent(EventType.valueOf(text(json, "type")), integer(json, "turn"), text(json, "description"),
                optionalText(json, "subject"), integer(json, "delta"), verb == null ? null : EventVerb.valueOf(verb),
                Instant.parse(text(json, "timestamp")));
    }

    private static void check(boolean condition, String problem) {
        if (!condition) {
            throw new IllegalArgumentException(problem);
        }
    }

    private static JsonElement field(JsonObject json, String name) {
        JsonElement value = json.get(name);
        check(value != null && !value.isJsonNull(), "missing " + name);
        return value;
    }

    private static String text(JsonObject json, String name) {
        JsonElement value = field(json, name);
        check(value.isJsonPrimitive() && value.getAsJsonPrimitive().isString(), name + " is not text");
        return value.getAsString();
    }

    private static String optionalText(JsonObject json, String name) {
        JsonElement value = json.get(name);
        return value == null || value.isJsonNull() ? null : text(json, name);
    }

    private static int integer(JsonObject json, String name) {
        JsonElement value = field(json, name);
        check(value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber(), name + " is not a number");
        double number = value.getAsDouble();
        check(number == Math.rint(number) && Math.abs(number) <= Integer.MAX_VALUE, name + " is not a whole number");
        return value.getAsInt();
    }

    private static boolean bool(JsonObject json, String name) {
        JsonElement value = field(json, name);
        check(value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean(), name + " is not true or false");
        return value.getAsBoolean();
    }

    private static JsonObject object(JsonObject json, String name) {
        JsonElement value = field(json, name);
        check(value.isJsonObject(), name + " is not an object");
        return value.getAsJsonObject();
    }

    private static JsonArray array(JsonObject json, String name) {
        JsonElement value = field(json, name);
        check(value.isJsonArray(), name + " is not a list");
        return value.getAsJsonArray();
    }

    private static <T> List<T> list(JsonObject json, String name, Function<JsonElement, T> fromJson) {
        List<T> values = new ArrayList<>();
        for (JsonElement element : array(json, name)) {
            values.add(fromJson.apply(element));
        }
        return values;
    }
}
