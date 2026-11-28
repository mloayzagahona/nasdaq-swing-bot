/* (C)2026 */
package com.openclaw.bot.ai;

/**
 * {@code AiNarrator} is the module responsible for converting a structured, rule-based
 * event (like a swing high/low) into a coherent, human-readable narrative alert.
 * <p>
 * It acts as the intermediary between the mechanical detection logic and the final
 * communication layer (email/alerting). It typically calls a local Language Model (Ollama)
 * to perform the summarization and contextualization.
 * @author OpenClaw Development Team
 */
public interface AiNarrator {

  /**
   * Submits a highly specific, structured prompt to the underlying AI model.
   * <p>
   * The prompt contains all necessary market context (price, previous extreme, day range, etc.)
   * and a strict instruction set (e.g., "no markdown", "be concise") to ensure the
   * LLM output is immediately useful for a trader.
   * @param prompt The fully constructed prompt containing all necessary market data.
   * @return The generated narrative string.
   * @throws Exception if the underlying AI model call (e.g., Ollama connection) fails.
   */
  String narrate(String prompt) throws Exception;
}
