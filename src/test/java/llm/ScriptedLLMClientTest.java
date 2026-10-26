package llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptedLLMClientTest {

    private static final LlmRequest REQUEST = new LlmRequest("system", "user", true, 100);

    @Test
    void answersWithTheCannedResponsesInOrder() {
        ScriptedLLMClient client = new ScriptedLLMClient(List.of("first", "second"));

        assertTrue(client.isAvailable());
        assertEquals("first", client.complete(REQUEST).getText());
        LlmResponse second = client.complete(REQUEST);
        assertTrue(second.isOk());
        assertEquals("second", second.getText());
        assertNull(second.getError());
    }

    @Test
    void isUnavailableAndFailsOnceTheAnswersRunOut() {
        ScriptedLLMClient client = new ScriptedLLMClient(List.of("only"));
        client.complete(REQUEST);

        assertFalse(client.isAvailable());
        LlmResponse response = client.complete(REQUEST);
        assertFalse(response.isOk());
        assertNull(response.getText());
        assertEquals("No scripted response left", response.getError());
    }

    @Test
    void withoutResponsesItIsOffline() {
        ScriptedLLMClient offline = new ScriptedLLMClient(List.of());
        assertFalse(offline.isAvailable());
        assertEquals("scripted", offline.providerName());
    }

    @Test
    void requestsAndResponsesKeepTheirValuesAndRejectNonsense() {
        assertEquals("system", REQUEST.getSystemPrompt());
        assertEquals("user", REQUEST.getUserPrompt());
        assertTrue(REQUEST.isJsonMode());
        assertEquals(100, REQUEST.getMaxTokens());
        assertThrows(IllegalArgumentException.class, () -> new LlmRequest("s", "u", false, 0));
        assertThrows(IllegalArgumentException.class, () -> new LlmResponse(null, true, null));
        assertThrows(IllegalArgumentException.class, () -> new LlmResponse(null, false, " "));
        assertThrows(NullPointerException.class, () -> new ScriptedLLMClient(null));
    }
}
