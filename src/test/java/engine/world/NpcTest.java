package engine.world;

import engine.items.Item;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NPC profiles, finding NPCs in a location, and the NPCs and owned items of the default world. */
class NpcTest {

    private static NPC hilde() {
        return new NPC("herbalist", "Hilde", "herbalist", "quiet", List.of("Herbs grow by the mill."),
                List.of("She hides a map."), false);
    }

    @Test
    void keepsItsProfile() {
        NPC npc = hilde();

        assertEquals("herbalist", npc.getId());
        assertEquals("Hilde", npc.getName());
        assertEquals("herbalist", npc.getRole());
        assertEquals("quiet", npc.getPersonality());
        assertEquals(List.of("Herbs grow by the mill."), npc.getKnowledge());
        assertEquals(List.of("She hides a map."), npc.getSecrets());
        assertFalse(npc.isQuestGiver());
        assertEquals("Hilde (herbalist)", npc.describe());
        assertThrows(UnsupportedOperationException.class, () -> npc.getKnowledge().add("more"));
    }

    @Test
    void blankTextsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new NPC(" ", "A", "r", "p", List.of(), List.of(), false));
        assertThrows(IllegalArgumentException.class, () -> new NPC("a", "", "r", "p", List.of(), List.of(), false));
        assertThrows(IllegalArgumentException.class, () -> new NPC("a", "A", " ", "p", List.of(), List.of(), false));
        assertThrows(NullPointerException.class, () -> new NPC("a", "A", "r", "p", null, List.of(), false));
    }

    @Test
    void isCalledByItsNameRoleOrIdIgnoringCase() {
        NPC npc = new NPC("tavern-keeper", "Marta", "tavern keeper", "warm", List.of(), List.of(), false);

        assertTrue(npc.isCalled("marta"));
        assertTrue(npc.isCalled(" Tavern Keeper "));
        assertTrue(npc.isCalled("TAVERN-KEEPER"));
        assertTrue(npc.isCalled("the tavern keeper"));
        assertFalse(npc.isCalled("the"));
        assertFalse(npc.isCalled("keeper"));
        assertFalse(npc.isCalled(null));
    }

    @Test
    void aLocationFindsItsNpcsAndListsThemInItsDescription() {
        Location garden = new Location("garden", "Garden", "Rows of herbs.");
        NPC npc = hilde();

        assertNull(garden.findNpc("Hilde"));
        assertEquals("Garden\nRows of herbs.\nItems: none\nExits: none", garden.describe());

        garden.addNpc(npc);

        assertSame(npc, garden.findNpc("hilde"));
        assertSame(npc, garden.findNpc("HERBALIST"));
        assertNull(garden.findNpc("Brann"));
        assertEquals(List.of(npc), garden.getNpcs());
        assertEquals("Garden\nRows of herbs.\nItems: none\nPeople: Hilde (herbalist)\nExits: none", garden.describe());
        assertThrows(UnsupportedOperationException.class, () -> garden.getNpcs().clear());
    }

    @Test
    void theDefaultWorldHasTheBlacksmithInTheForgeAndTheTavernKeeperInTheTavern() {
        World world = World.createDefault();
        NPC brann = world.findLocation("forge").findNpc("blacksmith");
        NPC marta = world.findLocation("tavern").findNpc("Marta");

        assertNotNull(brann);
        assertEquals("Brann", brann.getName());
        assertEquals("blacksmith", brann.getId());
        assertTrue(brann.isQuestGiver(), "the blacksmith gives quests (SD07)");
        assertNotNull(marta);
        assertEquals("tavern-keeper", marta.getId());
        assertFalse(marta.isQuestGiver());
        for (Region region : world.getRegions()) {
            for (Location location : region.getLocations()) {
                if (!location.getId().equals("forge") && !location.getId().equals("tavern")) {
                    assertTrue(location.getNpcs().isEmpty(), location.getId());
                }
            }
        }
    }

    @Test
    void theBlacksmithOwnsTheForgeItemsAndTheMainPathItemsBelongToNobody() {
        World world = World.createDefault();
        Location forge = world.findLocation("forge");
        Location tavern = world.findLocation("tavern");

        assertEquals("blacksmith", forge.findItem("iron key").getOwnerId());
        assertEquals("blacksmith", forge.findItem("warhammer").getOwnerId());
        for (Item item : tavern.getItems()) {
            assertNull(item.getOwnerId(), item.getName());
        }
    }

    @Test
    void noSecretIsPartOfWhatAnNpcKnows() {
        World world = World.createDefault();
        for (Region region : world.getRegions()) {
            for (Location location : region.getLocations()) {
                for (NPC npc : location.getNpcs()) {
                    assertFalse(npc.getSecrets().isEmpty(), npc.getId());
                    for (String secret : npc.getSecrets()) {
                        assertTrue(npc.getKnowledge().stream().noneMatch(fact -> fact.contains(secret)), secret);
                    }
                }
            }
        }
    }
}
