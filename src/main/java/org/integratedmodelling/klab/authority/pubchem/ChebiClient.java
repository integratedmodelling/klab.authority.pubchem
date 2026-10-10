package org.integratedmodelling.klab.authority.pubchem;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.*;

/** Maintained OLS4 ChEBI vocabulary access. Only direct is-a parents become superclasses. */
final class ChebiClient {
  private final ChemicalHttpClient http;
  private final String endpoint;
  private final Duration timeout;
  private final java.util.concurrent.Semaphore requests = new java.util.concurrent.Semaphore(4);
  private record Cached(JsonNode value, long expires) {}
  private final Map<String, Cached> cache = new LinkedHashMap<>(16, 0.75f, true) {
    @Override protected boolean removeEldestEntry(Map.Entry<String, Cached> entry) { return size() > 512; }
  };
  ChebiClient(ChemicalHttpClient http, String endpoint, Duration timeout) {
    this.http = http; this.endpoint = endpoint; this.timeout = timeout;
  }
  String termUrl(String id) {
    return endpoint + "/ontologies/chebi/terms?iri=" + ChemicalHttpClient.encode("http://purl.obolibrary.org/obo/" + id.replace(':', '_'));
  }
  JsonNode term(String id) {
    var response = json(termUrl(id));
    if (response == null) throw new IllegalArgumentException("Unknown ChEBI term: " + id);
    var terms = terms(response);
    if (terms.size() != 1) throw new IllegalArgumentException("Missing or ambiguous ChEBI term: " + id);
    var term = terms.get(0);
    validate(term);
    if (!id.equals(term.path("obo_id").asText())) throw new IllegalStateException("Mismatched ChEBI term");
    return term;
  }
  List<String> parents(String id) {
    String url = endpoint + "/ontologies/chebi/parents?id=" + ChemicalHttpClient.encode(id) + "&size=100";
    var response = json(url);
    if (response == null) throw new IllegalStateException("ChEBI hierarchy unavailable");
    var terms = terms(response);
    if (response.path("page").path("totalPages").asInt(1) > 1)
      throw new IllegalStateException("ChEBI parent list exceeds supported page size");
    var ids = new TreeSet<String>();
    for (var term : terms) {
      validate(term);
      String parent = term.path("obo_id").asText();
      if (id.equals(parent)) throw new IllegalStateException("Self-parent in ChEBI hierarchy");
      ids.add(parent);
    }
    // OLS returns complete term representations with direct parents. Reuse those records
    // instead of paying for a second paced HTTP call per ancestor. Minimal/partial parent
    // records remain usable as edges but never replace a canonical term lookup.
    synchronized (cache) {
      var source = cache.get(url);
      if (source != null) for (var term : terms) {
        String parent = term.path("obo_id").asText();
        if (!term.path("annotation").isObject() || !term.path("description").isArray()
            || !term.path("_links").path("self").path("href").isTextual()
            || !("http://purl.obolibrary.org/obo/" + parent.replace(':', '_')).equals(term.path("iri").asText())) continue;
        String termUrl = termUrl(parent);
        var existing = cache.get(termUrl);
        if (existing == null || existing.expires() <= System.nanoTime()) {
          var wrapper = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
          wrapper.putObject("_embedded").putArray("terms").add(term.deepCopy());
          cache.put(termUrl, new Cached(wrapper, source.expires()));
        }
      }
    }
    return List.copyOf(ids);
  }
  /** Validate the entire structural ancestry, including when a Reasoner already knows a parent. */
  void validateHierarchy(String id) {
    // OLS term and parent responses are independent. Hydrate each frontier with at most
    // four requests in flight, then perform the same complete cycle/depth validation locally.
    var graph = new LinkedHashMap<String, List<String>>();
    Set<String> frontier = Set.of(id);
    var executor = java.util.concurrent.Executors.newFixedThreadPool(4, Thread.ofVirtual().factory());
    try {
      int depth = 0;
      while (!frontier.isEmpty()) {
        if (depth++ > 64 || graph.size() + frontier.size() > 256)
          throw new IllegalStateException("ChEBI hierarchy exceeds validation limit");
        var terms = new LinkedHashMap<String, java.util.concurrent.CompletableFuture<JsonNode>>();
        var parents = new LinkedHashMap<String, java.util.concurrent.CompletableFuture<List<String>>>();
        for (String node : frontier) {
          terms.put(node, java.util.concurrent.CompletableFuture.supplyAsync(() -> term(node), executor));
          parents.put(node, java.util.concurrent.CompletableFuture.supplyAsync(() -> parents(node), executor));
        }
        var next = new LinkedHashSet<String>();
        for (String node : frontier) {
          terms.get(node).join();
          var edges = parents.get(node).join();
          graph.put(node, edges);
          next.addAll(edges);
        }
        next.removeAll(graph.keySet());
        frontier = next;
      }
      visit(id, graph, new HashSet<>(), new HashSet<>(), 0);
    } catch (java.util.concurrent.CompletionException failure) {
      if (failure.getCause() instanceof RuntimeException cause) throw cause;
      throw failure;
    } finally {
      executor.shutdownNow();
    }
  }
  private void visit(String id, Map<String, List<String>> graph, Set<String> active, Set<String> done, int depth) {
    if (done.contains(id)) return;
    if (depth > 64 || done.size() + active.size() >= 256)
      throw new IllegalStateException("ChEBI hierarchy exceeds validation limit");
    if (!active.add(id)) throw new IllegalStateException("Cycle in ChEBI hierarchy at " + id);
    for (String parent : graph.get(id)) visit(parent, graph, active, done, depth + 1);
    active.remove(id);
    done.add(id);
  }
  private JsonNode json(String url) {
    synchronized (cache) {
      var entry = cache.get(url);
      if (entry != null && entry.expires() > System.nanoTime()) return entry.value();
    }
    try { requests.acquire(); }
    catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("ChEBI lookup interrupted", interrupted);
    }
    try {
      synchronized (cache) {
        var entry = cache.get(url);
        if (entry != null && entry.expires() > System.nanoTime()) return entry.value();
      }
      var response = http.json(url, timeout);
      if (response != null) synchronized (cache) {
        cache.put(url, new Cached(response, System.nanoTime() + Duration.ofMinutes(5).toNanos()));
      }
      return response;
    } finally { requests.release(); }
  }
  record Search(List<String> ids, boolean truncated) {}
  Search search(String query, int limit, boolean exact) {
    var response = http.json(endpoint + "/search?q=" + ChemicalHttpClient.encode(query)
        + "&ontology=chebi&type=class&queryFields=label,synonym&obsoletes=false&local=true&rows=" + limit, timeout);
    if (response == null) throw new IllegalStateException("ChEBI search unavailable");
    var docs = response.path("response").path("docs");
    if (!docs.isArray() || !response.path("response").path("numFound").canConvertToInt())
      throw new IllegalStateException("Invalid ChEBI search response");
    var ids = new LinkedHashSet<String>();
    for (var doc : docs) {
      if (!"chebi".equals(doc.path("ontology_name").asText()) || !"class".equals(doc.path("type").asText()))
        throw new IllegalStateException("ChEBI search returned another vocabulary or entity type");
      String id = doc.path("obo_id").asText();
      if (!id.matches("CHEBI:[1-9][0-9]*")) throw new IllegalStateException("Invalid ChEBI search identifier");
      boolean matches = query.equalsIgnoreCase(doc.path("label").asText());
      for (var synonym : doc.path("exact_synonyms")) matches |= query.equalsIgnoreCase(synonym.asText());
      if (!exact || matches) ids.add(id);
    }
    return new Search(List.copyOf(ids), response.path("response").path("numFound").asInt() > docs.size());
  }
  static String inchi(JsonNode term) {
    var values = term.path("annotation").path("inchi_string");
    return values.isArray() && values.size() == 1 ? values.get(0).asText() : "";
  }
  private static JsonNode terms(JsonNode response) {
    var terms = response.path("_embedded").path("terms");
    if (terms.isMissingNode() && response.path("page").path("totalElements").asInt(-1) == 0)
      return new com.fasterxml.jackson.databind.ObjectMapper().createArrayNode();
    if (!terms.isArray()) throw new IllegalStateException("OLS omitted terms array");
    return terms;
  }
  private static void validate(JsonNode term) {
    if (!term.path("obo_id").asText().matches("CHEBI:[1-9][0-9]*")
        || !"chebi".equals(term.path("ontology_name").asText()) || term.path("label").asText().isBlank()
        || !term.path("is_obsolete").isBoolean() || term.path("is_obsolete").asBoolean()
        || !term.path("is_defining_ontology").asBoolean(false))
      throw new IllegalStateException("Invalid, imported or obsolete ChEBI term");
  }
}
