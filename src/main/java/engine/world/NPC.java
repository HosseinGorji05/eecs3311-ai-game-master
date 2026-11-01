package engine.world;

import java.util.List;
import java.util.Objects;

/**
 * A non-player character with a fixed profile: name, role, personality, what they know and what
 * they will never reveal (F03). NPCs live in a {@link Location}; an item whose
 * {@code ownerId} is an NPC's {@link #getId() id} belongs to that NPC.
 *
 * <p>The language model speaks for the NPC but never changes the profile. The secrets are never
 * put into a prompt; they are only used to reject a reply that happens to contain one.
 */
public class NPC {

    private final String id;
    private final String name;
    private final String role;
    private final String personality;
    private final List<String> knowledge;
    private final List<String> secrets;
    private final boolean questGiver;

    /**
     * @throws IllegalArgumentException if the id, name, role or personality is blank
     */
    public NPC(String id, String name, String role, String personality, List<String> knowledge,
               List<String> secrets, boolean questGiver) {
        this.id = requireText(id, "id");
        this.name = requireText(name, "name");
        this.role = requireText(role, "role");
        this.personality = requireText(personality, "personality");
        this.knowledge = List.copyOf(Objects.requireNonNull(knowledge, "knowledge"));
        this.secrets = List.copyOf(Objects.requireNonNull(secrets, "secrets"));
        this.questGiver = questGiver;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    /** What the NPC does, e.g. "blacksmith"; the player can also address the NPC by it. */
    public String getRole() {
        return role;
    }

    public String getPersonality() {
        return personality;
    }

    /** What the NPC knows and may tell, as a read-only list. */
    public List<String> getKnowledge() {
        return knowledge;
    }

    /** What the NPC will never reveal, as a read-only list. */
    public List<String> getSecrets() {
        return secrets;
    }

    public boolean isQuestGiver() {
        return questGiver;
    }

    /**
     * Whether the player means this NPC by {@code text}: its name, role or id, ignoring case and a
     * leading article ("the blacksmith").
     */
    public boolean isCalled(String text) {
        if (text == null) {
            return false;
        }
        String wanted = text.trim().replaceFirst("(?i)^(?:the|a|an)\\s+", "");
        return name.equalsIgnoreCase(wanted) || role.equalsIgnoreCase(wanted) || id.equalsIgnoreCase(wanted);
    }

    /** "Brann (blacksmith)". */
    public String describe() {
        return name + " (" + role + ")";
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException("NPC " + field + " must not be blank");
        }
        return value;
    }
}
