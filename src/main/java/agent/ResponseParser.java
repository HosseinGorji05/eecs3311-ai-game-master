package agent;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a model's JSON answer into an object. Models often wrap the JSON, so besides the plain text
 * it tries the contents of a Markdown code fence anywhere in the answer, and then the first balanced
 * {@code {...}} or {@code [...]} with text before or after it.
 */
public class ResponseParser {

    private static final Pattern CODE_FENCE = Pattern.compile("```[A-Za-z]*\\s*\\n?(.*?)```", Pattern.DOTALL);

    private final Gson gson = new Gson();

    /**
     * @return the parsed object, or {@code null} if the text is missing, empty or contains no
     *         well-formed JSON for {@code type}
     */
    public <T> T parseJson(String text, Class<T> type) {
        Objects.requireNonNull(type, "type");
        if (text == null) {
            return null;
        }
        for (String candidate : candidates(text.trim())) {
            try {
                T parsed = gson.fromJson(candidate, type);
                if (parsed != null) {
                    return parsed;
                }
            } catch (JsonParseException malformed) {
                // try the next candidate
            }
        }
        return null;
    }

    /** The whole text, the fenced block's contents, then the first balanced JSON value in the text. */
    private static List<String> candidates(String text) {
        List<String> candidates = new ArrayList<>();
        if (text.isEmpty()) {
            return candidates;
        }
        candidates.add(text);
        Matcher fence = CODE_FENCE.matcher(text);
        if (fence.find()) {
            candidates.add(fence.group(1).trim());
        }
        String embedded = firstBalancedJson(text);
        if (embedded != null) {
            candidates.add(embedded);
        }
        return candidates;
    }

    /** The first {@code {...}} or {@code [...]} whose brackets balance outside of strings, or {@code null}. */
    private static String firstBalancedJson(String text) {
        int start = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{' || c == '[') {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }
}
