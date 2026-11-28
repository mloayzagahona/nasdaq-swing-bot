/* (C)2026 */
package com.openclaw.bot.email;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Offline sender: writes the alert as an RFC-822-style .eml file into the capture directory. Used
 * when no SMTP host is configured so the bot never loses an alert silently.
 */
public final class FileEmailSender implements EmailSender {

  private final Path dir;
  private final String from;
  private final String to;

  public FileEmailSender(Path dir, String from, String to) {
    this.dir = dir;
    this.from = from;
    this.to = to;
  }

  @Override
  public void send(String subject, String body) throws IOException {
    Files.createDirectories(dir);
    String ts =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS")
            .withZone(ZoneId.systemDefault())
            .format(Instant.now());
    Path out = dir.resolve("alert-" + ts + ".eml");
    int n = 1;
    while (Files.exists(out)) {
      out = dir.resolve("alert-" + ts + "-" + (n++) + ".eml");
    }
    String eml =
        "From: "
            + from
            + "\n"
            + "To: "
            + to
            + "\n"
            + "Date: "
            + Instant.now()
            + "\n"
            + "Subject: "
            + subject
            + "\n"
            + "\n"
            + body
            + "\n";
    Files.writeString(out, eml);
    System.out.println("[email] alert written to " + out.toAbsolutePath());
  }
}
