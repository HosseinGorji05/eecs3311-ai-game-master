package agent;

import com.google.gson.JsonElement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Finding the JSON in a model's reply. */
class ResponseParserTest {

    private final ResponseParser parser = new ResponseParser();

    private String parsed(String text) {
        JsonElement json = parser.parseJson(text, JsonElement.class);
        return json == null ? null : json.toString();
    }

    @Test
    void plainJsonIsParsed() {
        assertEquals("{\"a\":1}", parsed("{\"a\":1}"));
        assertEquals("[1,2]", parsed(" [1, 2] "));
    }

    @Test
    void aCodeFenceAnywhereIsUnwrapped() {
        assertEquals("{\"a\":1}", parsed("```json\n{\"a\":1}\n```"));
        assertEquals("{\"a\":1}", parsed("Result:\n```\n{\"a\":1}\n```\nDone."));
        assertEquals("{\"a\":1}", parsed("```json {\"a\":1} ```"));
    }

    @Test
    void textBeforeAndAfterTheJsonIsIgnored() {
        assertEquals("{\"a\":1}", parsed("Sure! {\"a\":1}"));
        assertEquals("{\"a\":1}", parsed("{\"a\":1} Hope that helps {really}."));
        assertEquals("[{\"a\":1}]", parsed("Actions: [{\"a\":1}] - enjoy"));
    }

    @Test
    void bracketsInsideStringsDoNotEndTheJson() {
        assertEquals("{\"a\":\"} and { and \\\"quoted\\\" ]\"}",
                parsed("Here: {\"a\":\"} and { and \\\"quoted\\\" ]\"} trailing }"));
    }

    @Test
    void textWithoutJsonGivesNull() {
        assertNull(parsed(null));
        assertNull(parsed(""));
        assertNull(parsed("   "));
        assertNull(parsed("{oops"));
        assertNull(parsed("I cannot do that. {"));
        assertNull(parser.parseJson("no json here", Integer.class));
    }

    @Test
    void theTypeIsStillChecked() {
        assertNull(parser.parseJson("Answer: {\"a\":1}", Integer.class));
        assertEquals(42, parser.parseJson("42", Integer.class));
    }
}
