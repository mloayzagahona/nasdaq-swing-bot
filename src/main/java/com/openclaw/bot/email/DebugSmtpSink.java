/* (C)2026 */
package com.openclaw.bot.email;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Minimal SMTP server on the loopback interface used for tests and demos. It performs a real SMTP
 * conversation (EHLO/MAIL/RCPT/DATA/QUIT) and captures the message payload — proving the bot's
 * delivery path end-to-end without any external credentials. Captured messages land in {@code
 * capture/debug-smtp.log}.
 */
public final class DebugSmtpSink implements EmailSender, AutoCloseable {

  private final ServerSocket server;
  private final Thread acceptor;
  private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
  private final Path logFile;
  private volatile boolean running = true;
  private int count = 0;

  public DebugSmtpSink(int port, Path logFile) throws IOException {
    this.logFile = logFile;
    this.server = new ServerSocket(port, 8, InetAddress.getLoopbackAddress());
    this.acceptor = new Thread(this::acceptLoop, "debug-smtp-sink");
    this.acceptor.setDaemon(true);
    this.acceptor.start();
  }

  private void acceptLoop() {
    while (running) {
      try (Socket s = server.accept()) {
        handle(s);
      } catch (IOException e) {
        if (running) System.err.println("[smtp-sink] accept error: " + e.getMessage());
      }
    }
  }

  private void handle(Socket s) throws IOException {
    s.setSoTimeout(30000);
    BufferedReader in =
        new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
    PrintWriter out = new PrintWriter(s.getOutputStream(), true, StandardCharsets.UTF_8);
    out.println("220 localhost ESMTP debug-sink");
    String line;
    StringBuilder data = new StringBuilder();
    boolean inData = false;
    while ((line = in.readLine()) != null) {
      String up = line.toUpperCase();
      if (inData) {
        if (line.equals(".")) {
          inData = false;
          String msg = data.toString();
          count++;
          Files.createDirectories(logFile.getParent());
          Files.writeString(
              logFile,
              "=== MESSAGE " + count + " ===\n" + msg + "\n\n",
              StandardCharsets.UTF_8,
              StandardOpenOption.CREATE,
              StandardOpenOption.APPEND);
          messages.offer(msg);
          out.println("250 OK: queued");
        } else {
          data.append(line).append("\n");
        }
      } else if (up.startsWith("EHLO")) {
        out.println("250-localhost\r\n250 SIZE 10485760");
      } else if (up.startsWith("HELO")) {
        out.println("250 localhost");
      } else if (up.startsWith("MAIL FROM") || up.startsWith("RCPT TO")) {
        out.println("250 OK");
      } else if (up.startsWith("DATA")) {
        inData = true;
        data.setLength(0);
        out.println("354 End data with <CR><LF>.<CR><LF>");
      } else if (up.startsWith("RSET")) {
        out.println("250 OK");
      } else if (up.startsWith("QUIT")) {
        out.println("221 Bye");
        break;
      } else {
        out.println("250 OK");
      }
    }
    out.flush();
  }

  @Override
  public void send(String subject, String body) throws Exception {
    // Wait until a message is captured (the bot's SMTP client will have
    // connected to us and delivered its DATA payload).
    String msg = messages.poll(30, TimeUnit.SECONDS);
    if (msg == null) throw new IOException("debug SMTP sink: no message captured within 30s");
  }

  public int captured() {
    return count;
  }

  @Override
  public void close() {
    running = false;
    try {
      server.close();
    } catch (IOException ignored) {
    }
  }
}
