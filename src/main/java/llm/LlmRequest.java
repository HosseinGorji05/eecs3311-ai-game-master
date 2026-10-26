package llm;

import java.util.Objects;

/** One prompt for a language model: system and user text, whether JSON is required, and an answer length limit. */
public class LlmRequest {

    private final String systemPrompt;
    private final String userPrompt;
    private final boolean jsonMode;
    private final int maxTokens;

    /** @throws IllegalArgumentException if {@code maxTokens} is not positive */
    public LlmRequest(String systemPrompt, String userPrompt, boolean jsonMode, int maxTokens) {
        this.systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt");
        this.userPrompt = Objects.requireNonNull(userPrompt, "userPrompt");
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive: " + maxTokens);
        }
        this.jsonMode = jsonMode;
        this.maxTokens = maxTokens;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public String getUserPrompt() {
        return userPrompt;
    }

    public boolean isJsonMode() {
        return jsonMode;
    }

    public int getMaxTokens() {
        return maxTokens;
    }
}
