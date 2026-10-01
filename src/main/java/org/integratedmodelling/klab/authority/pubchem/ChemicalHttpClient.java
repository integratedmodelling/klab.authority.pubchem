package org.integratedmodelling.klab.authority.pubchem;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow.Subscription;

/** Anonymous read-only transport with bounded bodies, complete deadlines and shared pacing. */
final class ChemicalHttpClient {
  private static final int MAX_BYTES = 4 * 1024 * 1024;
  private static long nextRequest;
  // OLS4 currently closes Java HTTP/2 connections with GOAWAY. HTTP/1.1 works for both providers.
  private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(Duration.ofSeconds(10)).build();
  private final ObjectMapper mapper = new ObjectMapper();
  record Resource(byte[] body, String mediaType) {}

  static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  JsonNode json(String url, Duration timeout) {
    var resource = read(url, timeout, "application/json");
    if (resource == null) return null;
    if (!"application/json".equals(resource.mediaType()))
      throw new IllegalStateException("Expected application/json at " + url);
    try {
      var node = mapper.readTree(resource.body());
      if (node == null || !node.isObject() || node.has("Fault") || node.has("Waiting"))
        throw new IllegalStateException("Invalid or incomplete chemical API response at " + url);
      return node;
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Malformed chemical API JSON at " + url, e);
    }
  }

  Resource read(String url, Duration timeout, String accept) {
    long deadline = System.nanoTime() + timeout.toNanos();
    for (int attempt = 0; attempt < 3; attempt++) {
      pace(deadline);
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) throw new IllegalStateException("Chemical API request timed out");
      var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofNanos(remaining))
          .header("Accept", accept).header("User-Agent", "klab.authority.pubchem/1.0")
          .GET().build();
      var pending = client.sendAsync(request, info -> new BoundedBody());
      try {
        var response = pending.get(remaining, TimeUnit.NANOSECONDS);
        int status = response.statusCode();
        if (status == 404) return null;
        if ((status == 429 || status == 503) && attempt < 2) {
          long seconds = 1;
          try { seconds = Long.parseLong(response.headers().firstValue("Retry-After").orElse("1")); }
          catch (NumberFormatException ignored) { /* HTTP-date falls back to a conservative delay. */ }
          if (seconds < 0 || seconds > 30 || Duration.ofSeconds(seconds).toNanos() >= deadline - System.nanoTime())
            throw new IllegalStateException("Chemical API temporarily unavailable (HTTP " + status + ")");
          Thread.sleep(seconds * 1000);
          continue;
        }
        if (status != 200) throw new IllegalStateException("Chemical API HTTP " + status + " at " + url);
        String type = response.headers().firstValue("Content-Type").orElse("")
            .split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return new Resource(response.body(), type);
      } catch (InterruptedException e) {
        pending.cancel(true);
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Chemical API request interrupted", e);
      } catch (TimeoutException e) {
        pending.cancel(true);
        throw new IllegalStateException("Chemical API request timed out at " + url, e);
      } catch (ExecutionException e) {
        throw new IllegalStateException("Chemical API request failed at " + url + ": " + e.getCause().getMessage(), e);
      }
    }
    throw new IllegalStateException("Chemical API retry limit reached");
  }

  private static synchronized void pace(long deadline) {
    long now = System.nanoTime();
    long wait = Math.max(0, nextRequest - now);
    if (wait >= deadline - now) throw new IllegalStateException("Chemical API pacing exceeded timeout");
    try { TimeUnit.NANOSECONDS.sleep(wait); }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Chemical API pacing interrupted", e);
    }
    nextRequest = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250);
  }

  private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private Subscription subscription;
    @Override public CompletionStage<byte[]> getBody() { return result; }
    @Override public void onSubscribe(Subscription subscription) {
      this.subscription = subscription;
      subscription.request(1);
    }
    @Override public void onNext(List<ByteBuffer> buffers) {
      for (var buffer : buffers) {
        if (buffer.remaining() > MAX_BYTES - bytes.size()) {
          subscription.cancel();
          result.completeExceptionally(new IllegalStateException("Chemical API response exceeds 4 MiB"));
          return;
        }
        byte[] chunk = new byte[buffer.remaining()];
        buffer.get(chunk);
        bytes.writeBytes(chunk);
      }
      subscription.request(1);
    }
    @Override public void onError(Throwable error) { result.completeExceptionally(error); }
    @Override public void onComplete() { result.complete(bytes.toByteArray()); }
  }
}
