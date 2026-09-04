package com.quzzar.kithkyn.llm;

/**
 * A validated decision returned by the LLM.
 *
 * @param choice      the chosen option, exactly as it appeared in the options list
 * @param choiceIndex index of the chosen option in the options list that was passed in
 * @param reason      short in-character explanation from the model (may be empty)
 */
public record LlmDecision(String choice, int choiceIndex, String reason) {
}
