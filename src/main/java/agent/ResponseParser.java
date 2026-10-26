package agent;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.util.Objects;

/** Reads a model's JSON answer into an object, tolerating a Markdown code fence around it. */
public class ResponseParser {

    private final Gson gson = new Gson();

    /**
     * @return the parsed object, or {@code null} if the text is missing, empty or not well-formed
     *         JSON for {@code type}
     */
    public <T> T parseJson(String text, Class<T> type) {
        Objects.requireNonNull(type, "type");
        if (text == null) {
            return null;
        }
        try {
            return gson.fromJson(stripCodeFence(text.trim()), type);
        } catch (JsonParseException malformed) {
            return null;
        }
    }

    /** Removes a surrounding {@code ```json ... ```} fence, which models often add. */
    private static String stripCodeFence(String text) {
        if (!text.startsWith("```")) {
            return text;
        }
        int firstLineEnd = text.indexOf('\n');
        int closingFence = text.lastIndexOf("```");
        if (firstLineEnd < 0 || closingFence <= firstLineEnd) {
            return text;
        }
        return text.substring(firstLineEnd + 1, closingFence).trim();
    }
}
