package com.openclaw.bot.email;

/** Sends an alert. Implementations: SMTP (real), debug sink (protocol capture), file (offline). */
public interface EmailSender {

    void send(String subject, String body) throws Exception;
}
