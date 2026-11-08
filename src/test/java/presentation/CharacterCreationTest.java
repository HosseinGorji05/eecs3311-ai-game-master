package presentation;

import engine.character.CharacterClass;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The New Game screen's backstory step and its button rules, without a display (F01, SD01). */
class CharacterCreationTest {

    private final CharacterCreation creation = new CharacterCreation();
    private final StatAllocation valid = new StatAllocation("Ari", CharacterClass.ROGUE, 2, 6, 2);
    private final StatAllocation invalid = new StatAllocation("Ari", CharacterClass.ROGUE, 4, 6, 2);

    @Test
    void generateNeedsAValidFormAndNoPendingCall() {
        assertTrue(creation.canGenerate(valid, false));
        assertFalse(creation.canGenerate(valid, true));
        assertFalse(creation.canGenerate(invalid, false));
    }

    @Test
    void regenerateAndStartNeedAShownBackstoryAndNoPendingCall() {
        assertFalse(creation.canRegenerateOrStart(false), "nothing generated yet");

        assertTrue(creation.backstoryWritten(creation.version(), "A story."));

        assertTrue(creation.canRegenerateOrStart(false));
        assertFalse(creation.canRegenerateOrStart(true));
        assertEquals("A story.", creation.getBackstory());
    }

    @Test
    void regeneratingReplacesTheShownBackstoryAsOftenAsWanted() {
        for (String text : new String[] {"One.", "Two.", "Three."}) {
            assertTrue(creation.backstoryWritten(creation.version(), text));
        }
        assertEquals("Three.", creation.getBackstory());
    }

    @Test
    void editingTheFormDiscardsTheBackstory() {
        creation.backstoryWritten(creation.version(), "A story.");

        creation.formChanged();

        assertFalse(creation.hasBackstory());
        assertNull(creation.getBackstory());
        assertFalse(creation.canRegenerateOrStart(false));
    }

    @Test
    void aBackstoryForAFormThatChangedMeanwhileIsIgnored() {
        int requested = creation.version();

        creation.formChanged();

        assertFalse(creation.backstoryWritten(requested, "For the old character."));
        assertFalse(creation.hasBackstory());
        assertTrue(creation.backstoryWritten(creation.version(), "For the new one."));
    }

    @Test
    void theStoryPanelIntroducesTheBackstoryOnce() {
        assertEquals("Ari's story:\nA story.", GuiText.backstoryIntro("Ari", "A story."));
    }
}
