package engine.world;

import engine.items.Consumable;
import engine.items.Container;
import engine.items.Item;
import engine.items.KeyItem;
import engine.items.Weapon;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The fixed, hand-designed game world: its regions, their locations and the start location.
 *
 * <p>The LLM may describe the world but never changes its structure; every location and exit
 * comes from {@link #createDefault()}.
 *
 * <p>Constraints: location ids are unique across the world, and the start location belongs to
 * one of its regions.
 */
public class World {

    /** Name of the key that opens the locked door to the Tool Shed. */
    static final String SHED_KEY = "shed key";

    private final List<Region> regions;
    private final Location startLocation;

    public World(List<Region> regions, Location startLocation) {
        Objects.requireNonNull(regions, "regions");
        Objects.requireNonNull(startLocation, "startLocation");
        if (regions.isEmpty()) {
            throw new IllegalArgumentException("A world needs at least one region");
        }
        this.regions = List.copyOf(regions);
        Set<String> ids = new HashSet<>();
        for (Region region : this.regions) {
            for (Location location : region.getLocations()) {
                if (!ids.add(location.getId())) {
                    throw new IllegalArgumentException("Duplicate location id: " + location.getId());
                }
            }
        }
        if (findLocation(startLocation.getId()) != startLocation) {
            throw new IllegalArgumentException("Start location is not part of the world: " + startLocation.getId());
        }
        this.startLocation = startLocation;
    }

    /**
     * Builds the starter world (Stage 1 decision "World size"): the Village and the Forest with
     * eight locations in total. The Tool Shed, east of the Village Square, is locked; its shed key
     * lies in the Tavern.
     *
     * <pre>
     *                  Tavern
     *                    |
     *   Forge ---- Village Square ==locked== Tool Shed
     *                    |
     *   Wolf Den -- Forest Edge -- Old Mill
     *                    |
     *               Bandit Camp
     * </pre>
     */
    public static World createDefault() {
        Location square = new Location("village-square", "Village Square",
                "A muddy square around an old well. The village is quieter than it should be.");
        Location tavern = new Location("tavern", "Tavern",
                "A smoky room with a low ceiling. A few villagers whisper over their mugs.");
        Location forge = new Location("forge", "Blacksmith's Forge",
                "The forge is still warm. Tools and half-finished blades hang on the walls.");
        Location shed = new Location("tool-shed", "Tool Shed",
                "A cramped shed that smells of oil and old wood.");
        Location forestEdge = new Location("forest-edge", "Forest Edge",
                "Tall pines close in around a narrow path leading away from the village.");
        Location wolfDen = new Location("wolf-den", "Wolf Den",
                "A rocky hollow littered with bones. Something growls in the dark.");
        Location oldMill = new Location("old-mill", "Old Mill",
                "A broken water wheel creaks beside a stream. The mill has been empty for years.");
        Location banditCamp = new Location("bandit-camp", "Bandit Camp",
                "Tents around a smouldering fire, deep in the forest. This is where the relic was taken.");

        connect(square, Direction.NORTH, tavern, Direction.SOUTH);
        connect(square, Direction.WEST, forge, Direction.EAST);
        connectLocked(square, Direction.EAST, shed, Direction.WEST, SHED_KEY);
        connect(square, Direction.SOUTH, forestEdge, Direction.NORTH);
        connect(forestEdge, Direction.WEST, wolfDen, Direction.EAST);
        connect(forestEdge, Direction.EAST, oldMill, Direction.WEST);
        connect(forestEdge, Direction.SOUTH, banditCamp, Direction.NORTH);

        tavern.addItem(new KeyItem(SHED_KEY, 0.1));
        tavern.addItem(new Consumable("bread", 0.3, 2));
        forge.addItem(new KeyItem("iron key", 0.2));
        forge.addItem(new Weapon("warhammer", 8.0, 8));
        shed.addItem(new Weapon("hand axe", 2.0, 5));
        shed.addItem(new Container("backpack", 1.0, 8));
        oldMill.addItem(new Consumable("healing herbs", 0.2, 5));

        Region village = new Region("Village", List.of(square, tavern, forge, shed));
        Region forest = new Region("Forest", List.of(forestEdge, wolfDen, oldMill, banditCamp));
        return new World(List.of(village, forest), square);
    }

    /** The location with this id, or {@code null} if there is none. */
    public Location findLocation(String id) {
        for (Region region : regions) {
            for (Location location : region.getLocations()) {
                if (location.getId().equals(id)) {
                    return location;
                }
            }
        }
        return null;
    }

    public Location getStartLocation() {
        return startLocation;
    }

    /** The regions of this world, as a read-only list. */
    public List<Region> getRegions() {
        return regions;
    }

    /**
     * A plain-text overview of every location with its id, items and exits, one line each, for
     * prompts that must only refer to things that exist.
     */
    public String summarize() {
        StringBuilder text = new StringBuilder();
        for (Region region : regions) {
            text.append(region.getName()).append(":\n");
            for (Location location : region.getLocations()) {
                String items = location.getItems().isEmpty()
                        ? "none"
                        : location.getItems().stream().map(Item::getName).collect(Collectors.joining(", "));
                text.append(String.format(Locale.ROOT, "- %s [%s]; items: %s; exits: %s\n",
                        location.getName(), location.getId(), items, location.describeExits()));
            }
        }
        return text.toString();
    }

    private static void connect(Location a, Direction aToB, Location b, Direction bToA) {
        a.addExit(new Exit(exitId(a, aToB), aToB, b));
        b.addExit(new Exit(exitId(b, bToA), bToA, a));
    }

    /** Connects two locations with a door that is locked from {@code a}'s side only. */
    private static void connectLocked(Location a, Direction aToB, Location b, Direction bToA, String keyName) {
        a.addExit(new Exit(exitId(a, aToB), aToB, b, keyName));
        b.addExit(new Exit(exitId(b, bToA), bToA, a));
    }

    private static String exitId(Location from, Direction direction) {
        return from.getId() + "-" + Location.directionName(direction);
    }
}
