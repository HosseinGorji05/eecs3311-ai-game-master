package engine.world;

import java.util.List;
import java.util.Objects;

/** A named part of the world, such as the Village or the Forest, made up of locations. */
public class Region {

    private final String name;
    private final List<Location> locations;

    public Region(String name, List<Location> locations) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Region name must not be blank");
        }
        Objects.requireNonNull(locations, "locations");
        if (locations.isEmpty()) {
            throw new IllegalArgumentException("Region " + name + " must have at least one location");
        }
        this.name = name;
        this.locations = List.copyOf(locations);
    }

    public String getName() {
        return name;
    }

    /** The locations of this region, as a read-only list. */
    public List<Location> getLocations() {
        return locations;
    }
}
