package org.integratedmodelling.klab.authority.pubchem;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.*;

/** Maintained OLS4 ChEBI vocabulary access. Only direct is-a parents become superclasses. */
final class ChebiClient {
  private final ChemicalHttpClient http;
  private final String endpoint;
  private final Duration timeout;
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
    var response = json(endpoint + "/ontologies/chebi/parents?id=" + ChemicalHttpClient.encode(id) + "&size=100");
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
    return List.copyOf(ids);
  }
  /** Validate the entire structural ancestry, including when a Reasoner already knows a parent. */
  void validateHierarchy(String id) {
    visit(id, new HashSet<>(), new HashSet<>(), 0);
  }
  private void visit(String id, Set<String> active, Set<String> done, int depth) {
    if (done.contains(id)) return;
    if (depth > 64 || done.size() + active.size() >= 256)
      throw new IllegalStateException("ChEBI hierarchy exceeds validation limit");
    if (!active.add(id)) throw new IllegalStateException("Cycle in ChEBI hierarchy at " + id);
    term(id);
    for (String parent : parents(id)) visit(parent, active, done, depth + 1);
    active.remove(id);
    done.add(id);
  }
  private synchronized JsonNode json(String url) {
    var entry = cache.get(url);
    long now = System.nanoTime();
    if (entry != null && entry.expires() > now) return entry.value();
    var response = http.json(url, timeout);
    if (response != null) cache.put(url, new Cached(response, now + Duration.ofMinutes(5).toNanos()));
    return response;
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
