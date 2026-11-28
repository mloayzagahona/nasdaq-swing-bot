/* (C)2026 */
package com.openclaw.bot.market;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FinnhubClientTest {

  private HttpServer server;

  private FinnhubClient clientResponding(String jsonBody, AtomicReference<String> requestPathRef)
      throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/quote",
        exchange -> {
          requestPathRef.set(exchange.getRequestURI().toString());
          byte[] body = jsonBody.getBytes();
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    HttpClient http = HttpClient.newHttpClient();
    return new FinnhubClient(
        "COMP", "test-key", "http://127.0.0.1:" + server.getAddress().getPort(), http);
  }

  @AfterEach
  void tearDown() {
    if (server != null) server.stop(0);
  }

  @Test
  void mapsNasdaqAliasesToFinnhubWireSymbols() throws IOException {
    AtomicReference<String> path = new AtomicReference<>();
    FinnhubClient client =
        clientResponding(
            "{\"c\":261.98,\"h\":263.31,\"l\":260.11,\"o\":261.78,\"pc\":259.45,\"t\":1735600000}",
            path);
    client.quote();
    assertTrue(
        path.get().contains("symbol=%5EIXIC"),
        "wire symbol should be %5EIXIC (^IXIC) for COMP, got: " + path.get());
    assertTrue(path.get().contains("token=test-key"));

    // NDX alias -> ^NDX
    path.set("");
    FinnhubClient ndx =
        new FinnhubClient(
            "NDX",
            "test-key",
            "http://127.0.0.1:" + server.getAddress().getPort(),
            HttpClient.newHttpClient());
    ndx.quote();
    assertTrue(
        path.get().contains("symbol=%5ENDX"),
        "wire symbol should be %5ENDX (^NDX) for NDX, got: " + path.get());

    // Already a wire symbol passes through untouched (URL-encoded)
    path.set("");
    FinnhubClient gspc =
        new FinnhubClient(
            "^GSPC",
            "test-key",
            "http://127.0.0.1:" + server.getAddress().getPort(),
            HttpClient.newHttpClient());
    gspc.quote();
    assertTrue(path.get().contains("symbol=%5EGSPC"));
  }

  @Test
  void parsesQuoteIntoQuoteRecord() throws IOException {
    AtomicReference<String> path = new AtomicReference<>();
    FinnhubClient client =
        clientResponding(
            "{\"c\":261.98,\"h\":263.31,\"l\":260.11,\"o\":261.78,\"pc\":259.45,\"t\":1735600000}",
            path);
    Quote q = client.quote();
    assertEquals("COMP", q.symbol());
    assertEquals(261.98, q.last(), 1e-9);
    assertEquals(259.45, q.prevClose(), 1e-9);
    assertEquals(263.31, q.dayHigh(), 1e-9);
    assertEquals(260.11, q.dayLow(), 1e-9);
    assertEquals(Instant.ofEpochSecond(1735600000L), q.ts());
  }

  @Test
  void toleratesMissingOptionalFields() throws IOException {
    AtomicReference<String> path = new AtomicReference<>();
    FinnhubClient client = clientResponding("{\"c\":100.5}", path);
    Quote q = client.quote();
    assertEquals(100.5, q.last(), 1e-9);
    assertTrue(Double.isNaN(q.prevClose()));
    assertEquals(100.5, q.dayHigh(), 1e-9);
    assertEquals(100.5, q.dayLow(), 1e-9);
  }

  @Test
  void surfacesApiErrorAsIOException() throws IOException {
    AtomicReference<String> path = new AtomicReference<>();
    FinnhubClient client = clientResponding("{\"error\":\"unauthorized\"}", path);
    IOException e = assertThrows(IOException.class, client::quote);
    assertTrue(e.getMessage().contains("unauthorized"));
  }

  @Test
  void requiresApiKey() {
    assertThrows(IllegalArgumentException.class, () -> new FinnhubClient("COMP", ""));
  }
}
