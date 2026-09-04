package com.quzzar.kithkyn.llm;

import java.util.List;

/**
 * A validated multi-pick returned by the LLM: every option it chose, which may
 * be none of them.
 *
 * @param choiceIndexes indexes into the options list that was passed in, in
 *                      the order the model named them, without repeats; empty
 *                      when it chose nothing
 * @param reason        short in-character explanation from the model (may be empty)
 */
public record LlmSelection(List<Integer> choiceIndexes, String reason) {
}
