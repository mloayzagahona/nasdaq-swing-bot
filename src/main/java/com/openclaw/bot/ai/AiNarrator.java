package com.openclaw.bot.ai;

/** Produces the human-readable narrative for an alert (the "AI" part of the bot). */
public interface AiNarrator {

    String narrate(String prompt) throws Exception;
}
