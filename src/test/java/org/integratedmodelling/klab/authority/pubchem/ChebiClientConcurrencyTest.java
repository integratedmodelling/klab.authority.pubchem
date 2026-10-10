package org.integratedmodelling.klab.authority.pubchem;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ChebiClientConcurrencyTest {
  @Test void hierarchyRequestsOverlapWithinTheBoundAndWarmTraversalUsesTheCache() throws Exception {
    var graph = Map.of(1, List.of(2, 3, 4), 2, List.of(5), 3, List.of(5), 4, List.of(5), 5, List.<Integer>of());
    var active = new AtomicInteger(); var maximum = new AtomicInteger(); var calls = new AtomicInteger();
    var rootRequests = new CountDownLatch(2);
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      server.setExecutor(workers);
      server.createContext("/", exchange -> {
        int concurrent = active.incrementAndGet(); maximum.accumulateAndGet(concurrent, Math::max); calls.incrementAndGet();
        try {
          String query = URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
          int id = Integer.parseInt(query.replaceFirst(".*CHEBI[:_]", "").replaceFirst("&.*", ""));
          if (id == 1) { rootRequests.countDown();
            if (!rootRequests.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("Term and parents were serialized"); }
          Thread.sleep(20);
          var ids = exchange.getRequestURI().getPath().endsWith("parents") ? graph.get(id) : List.of(id);
          var terms = ids.stream().map(n -> "{\"obo_id\":\"CHEBI:" + n
              + "\",\"ontology_name\":\"chebi\",\"label\":\"Term\",\"is_obsolete\":false,\"is_defining_ontology\":true}").toList();
          byte[] body = ("{\"_embedded\":{\"terms\":[" + String.join(",", terms) + "]}}").getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try (var output = exchange.getResponseBody()) { output.write(body); }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        finally { active.decrementAndGet(); exchange.close(); }
      });
      server.start();
      try {
        var client = new ChebiClient(new ChemicalHttpClient(), "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(5));
        client.validateHierarchy("CHEBI:1");
        assertTrue(maximum.get() >= 2); assertTrue(maximum.get() <= 4);
        assertEquals(10, calls.get()); // A shared ancestor is fetched once, with term and edges separately.
        client.validateHierarchy("CHEBI:1"); assertEquals(10, calls.get());
      } finally { server.stop(0); }
    }
  }
}
