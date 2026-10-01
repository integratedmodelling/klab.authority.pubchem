package org.integratedmodelling.klab.authority.pubchem;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.integratedmodelling.klab.api.collections.Pair;
import org.integratedmodelling.klab.api.knowledge.Codelist;
import org.integratedmodelling.klab.api.services.Authority;
import org.integratedmodelling.klab.api.services.runtime.Notification;

/** PubChem molecules and ChEBI chemical entities in independent worldview-local bridges. */
@org.integratedmodelling.klab.api.services.reasoner.Authority(
    urn = PubChemAuthority.URN, version = "1.0.0", embeddable = true,
    searchable = true, subAuthorities = {"COMPOUND", "CHEBI"})
public class PubChemAuthority implements Authority {
  public static final String URN = "klab.authority.pubchem";
  private static final Set<String> PARAMETERS = Set.of("urn", "endpoint", "chebiEndpoint",
      "timeoutSeconds", "searchLimit", "gifDepictions", "documentationDirectory");
  private final ChemicalHttpClient http;
  private final Map<String, Bridge> bridges;
  private final String filter;

  public PubChemAuthority() { this(new ChemicalHttpClient(), new ConcurrentHashMap<>(), null); }
  private PubChemAuthority(ChemicalHttpClient http, Map<String, Bridge> bridges, String filter) {
    this.http = http; this.bridges = bridges; this.filter = filter;
  }
  @Override public String getUrn() { return URN; }
  @Override public CachePolicy getCachePolicy() { return new CachePolicy("pubchem-chebi-1", 86400, 300, 300); }

  @Override public String configure(ConfigurationRequest request) {
    var parameters = request.parameters();
    if (!URN.equals(parameters.get("urn"))) throw new IllegalArgumentException("Wrong PubChem provider URN");
    for (var key : parameters.keySet())
      if (!PARAMETERS.contains(key)) throw new IllegalArgumentException("Unknown PubChem parameter: " + key);
    String endpoint = endpoint(parameters.getOrDefault("endpoint", "https://pubchem.ncbi.nlm.nih.gov/rest/pug"));
    String chebiEndpoint = endpoint(parameters.getOrDefault("chebiEndpoint", "https://www.ebi.ac.uk/ols4/api"));
    int limit = integer(parameters, "searchLimit", 10, 1, 50);
    var timeout = Duration.ofSeconds(integer(parameters, "timeoutSeconds", 20, 1, 120));
    Object gifs = parameters.getOrDefault("gifDepictions", Boolean.TRUE);
    if (!(gifs instanceof Boolean)) throw new IllegalArgumentException("gifDepictions must be a boolean");
    Path directory = ChemicalDocumentation.defaultDirectory();
    if (parameters.containsKey("documentationDirectory")) {
      if (!(parameters.get("documentationDirectory") instanceof String value) || value.isBlank())
        throw new IllegalArgumentException("documentationDirectory must be an absolute path string");
      directory = Path.of(value);
      if (!directory.isAbsolute()) throw new IllegalArgumentException("documentationDirectory must be absolute");
    }
    String id = UUID.randomUUID().toString();
    bridges.put(id, new Bridge(request, new PubChemClient(http, endpoint, timeout),
        new ChebiClient(http, chebiEndpoint, timeout), limit, (Boolean) gifs, directory));
    return id;
  }
  @Override public void releaseConfiguration(String configurationId) {
    if (configurationId != null) bridges.remove(configurationId);
  }

  @Override public Identity resolveIdentity(String configurationId, String identityId) {
    var bridge = bridge(configurationId);
    try { return resolve(bridge, input(identityId)); }
    catch (RuntimeException e) { return failure(bridge, identityId, e.getMessage()); }
  }

  private ChemicalIdentity resolve(Bridge bridge, String input) {
    if (input.matches("(?i)(CID[:_])?[1-9][0-9]*")) {
      String cid = input.replaceFirst("(?i)^CID[:_]", "");
      return compound(bridge, cid);
    }
    if (input.matches("(?i)CHEBI[:_][1-9][0-9]*")) {
      String id = input.toUpperCase(Locale.ROOT).replace('_', ':');
      var term = bridge.chebi.term(id);
      String inchi = ChebiClient.inchi(term);
      if (!inchi.isBlank()) {
        // Only structure-verified, unique mappings share the PubChem canonical concept.
        var cids = bridge.pubchem.cids("inchi", inchi);
        if (cids.size() == 1) {
          var properties = bridge.pubchem.properties(cids.get(0));
          if (!inchi.equals(properties.path("InChI").asText())) throw new IllegalStateException("Inconsistent ChEBI/PubChem InChI mapping");
          return compound(bridge, cids.get(0), properties, term);
        }
      }
      return chebi(bridge, id, term);
    }
    if (input.startsWith("InChI=")) return uniqueCompound(bridge, "inchi", input);
    if (input.matches("[A-Z]{14}-[A-Z]{10}-[A-Z]")) return uniqueCompound(bridge, "inchikey", input);
    throw new IllegalArgumentException("Expected CID, CHEBI, InChI or InChIKey; use search or reconcile for names");
  }
  private ChemicalIdentity uniqueCompound(Bridge bridge, String namespace, String input) {
    var cids = bridge.pubchem.cids(namespace, input);
    if (cids.size() != 1) throw new IllegalArgumentException("Structure identifier matched " + cids.size() + " compounds; explicit selection required");
    return compound(bridge, cids.get(0));
  }
  private ChemicalIdentity compound(Bridge bridge, String cid) {
    return compound(bridge, cid, bridge.pubchem.properties(cid), null);
  }
  private ChemicalIdentity compound(Bridge bridge, String cid, JsonNode properties, JsonNode knownTerm) {
    var parents = new TreeSet<String>();
    var matches = new TreeMap<String, JsonNode>();
    if (knownTerm != null) matches.put(knownTerm.path("obo_id").asText(), knownTerm);
    for (String id : bridge.pubchem.chebiReferences(cid)) {
      var term = matches.containsKey(id) ? matches.get(id) : bridge.chebi.term(id);
      if (properties.path("InChI").asText().equals(ChebiClient.inchi(term))) matches.put(id, term);
    }
    for (String id : matches.keySet()) {
      bridge.chebi.validateHierarchy(id);
      parents.addAll(bridge.chebi.parents(id));
    }
    // The corresponding ChEBI molecular term is an alias, never its own superclass.
    parents.removeAll(matches.keySet());
    var metadata = properties.deepCopy();
    if (!matches.isEmpty()) ((com.fasterxml.jackson.databind.node.ObjectNode) metadata).set("ChEBI", new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(matches));
    String id = "CID:" + cid;
    var docs = ChemicalDocumentation.build(id, properties.path("Title").asText(), metadata,
        List.copyOf(parents), bridge.pubchem, cid, bridge.pubchem.propertyUrl(cid),
        bridge.directory, bridge.gif);
    return identity(bridge, id, properties.path("Title").asText(), List.copyOf(parents), docs);
  }
  private ChemicalIdentity chebi(Bridge bridge, String id, JsonNode term) {
    bridge.chebi.validateHierarchy(id);
    var parents = bridge.chebi.parents(id);
    var docs = ChemicalDocumentation.build(id, term.path("label").asText(), term, parents,
        bridge.pubchem, null, bridge.chebi.termUrl(id), bridge.directory, false);
    return identity(bridge, id, term.path("label").asText(), parents, docs);
  }
  private ChemicalIdentity identity(Bridge bridge, String id, String label, List<String> parents,
      ChemicalDocumentation.Result docs) {
    return new ChemicalIdentity(id, id.replace(':', '_'), bridge.request.name(),
        parents.isEmpty() ? bridge.request.rootIdentity() : null, parents, docs.urls(),
        docs.description(), label, 1, bridge.request.name() + ":" + id.replace(':', '_'), docs.notifications());
  }

  @Override public List<Identity> search(String query, String subAuthority, String configurationId) {
    var bridge = bridge(configurationId);
    String selected = filter(subAuthority == null || subAuthority.isBlank() ? filter : subAuthority);
    if (filter != null && selected != null && !filter.equals(selected)) throw new IllegalArgumentException("Conflicting chemical search filters");
    if (query == null || query.isBlank()) return List.of();
    String value = input(query);
    var results = new LinkedHashMap<String, Identity>();
    if (isCode(value)) {
      var identity = resolve(bridge, value);
      results.put(identity.getId(), identity);
    } else {
      if (!"CHEBI".equals(selected)) {
        var ids = bridge.pubchem.cids("name", value);
        for (String cid : ids.stream().limit(bridge.limit).toList()) {
          var identity = compound(bridge, cid);
          results.put(identity.getId(), identity);
        }
      }
      if (!"COMPOUND".equals(selected)) {
        for (String id : bridge.chebi.search(value, bridge.limit, false).ids()) {
          var identity = resolve(bridge, id);
          results.putIfAbsent(identity.getId(), identity);
        }
      }
    }
    return List.copyOf(results.values().stream().limit(bridge.limit).toList());
  }

  @Override public Identity reconcile(String configurationId, Map<String, String> fields) {
    var bridge = bridge(configurationId);
    if (fields == null || !Set.of("name", "catalog").containsAll(fields.keySet())
        || fields.get("name") == null || fields.get("name").isBlank())
      throw new IllegalArgumentException("Reconciliation requires name and optional catalog (COMPOUND or CHEBI)");
    String selected = filter(fields.getOrDefault("catalog", filter));
    if (filter != null && selected != null && !filter.equals(selected)) throw new IllegalArgumentException("Conflicting chemical reconciliation filters");
    try {
      String name = input(fields.get("name"));
      var matches = new LinkedHashMap<String, Identity>();
      if (!"CHEBI".equals(selected)) {
        var cids = bridge.pubchem.cids("name", name);
        if (cids.size() > 1) return failure(bridge, name, "Ambiguous PubChem name; use search and select a code");
        if (cids.size() == 1) {
          var identity = compound(bridge, cids.get(0));
          matches.put(identity.getId(), identity);
        }
      }
      if (!"COMPOUND".equals(selected)) {
        var search = bridge.chebi.search(name, 100, true);
        if (search.truncated()) return failure(bridge, name, "ChEBI candidates exceed reconciliation limit; select a code");
        for (String id : search.ids()) {
          var identity = resolve(bridge, id);
          matches.putIfAbsent(identity.getId(), identity);
        }
      }
      if (matches.size() != 1) return failure(bridge, name, "Name matched " + matches.size() + " identities; explicit selection required");
      return matches.values().iterator().next();
    } catch (RuntimeException e) { return failure(bridge, fields.get("name"), e.getMessage()); }
  }

  @Override public Capabilities getCapabilities() {
    return new Capabilities() {
      @Override public String getDescription() { return "PubChem compounds and ChEBI chemical entities, including families, ions and mixtures"; }
      @Override public boolean isSearchable() { return true; }
      @Override public boolean isFuzzy() { return false; }
      @Override public boolean isReconciliationSupported() { return true; }
      @Override public boolean areSubAuthoritiesSearchFilters() { return true; }
      @Override public List<Pair<String, String>> getSubAuthorities() {
        return List.of(Pair.of("", "All chemical identities"), Pair.of("COMPOUND", "PubChem compounds"), Pair.of("CHEBI", "ChEBI ontology entities"));
      }
      @Override public List<String> getDocumentationFormats() { return List.of("text/markdown", "text/html", "application/json", "image/png", "image/gif"); }
      @Override public String getWorldview() { return null; }
    };
  }
  @Override public Map<String, Codelist> getCodelists() { return Map.of(); }
  @Override public Authority subAuthority(String catalog) {
    String selected = filter(catalog);
    return selected == null ? this : new PubChemAuthority(http, bridges, selected);
  }
  private Bridge bridge(String id) {
    var bridge = id == null ? null : bridges.get(id);
    if (bridge == null) throw new IllegalArgumentException("Unknown or released PubChem configuration ID");
    return bridge;
  }
  private ChemicalIdentity failure(Bridge bridge, String id, String message) {
    return new ChemicalIdentity(id, null, bridge.request.name(), null, List.of(), Map.of(),
        message, id, 0, null, List.of(Notification.error("PUBCHEM: " + message)));
  }
  private static String input(String value) {
    if (value == null || value.isBlank() || value.length() > 4096) throw new IllegalArgumentException("Chemical identifier/query must contain 1 to 4096 characters");
    return value.trim();
  }
  private static boolean isCode(String value) {
    return value.matches("(?i)(CID[:_])?[1-9][0-9]*|CHEBI[:_][1-9][0-9]*")
        || value.startsWith("InChI=") || value.matches("[A-Z]{14}-[A-Z]{10}-[A-Z]");
  }
  private static String filter(String value) {
    if (value == null || value.isBlank()) return null;
    String result = value.toUpperCase(Locale.ROOT);
    if (!Set.of("COMPOUND", "CHEBI").contains(result)) throw new IllegalArgumentException("Unknown chemical catalog: " + value);
    return result;
  }
  private static int integer(Map<String, Object> parameters, String key, int fallback, int min, int max) {
    try {
      int value = new java.math.BigDecimal(String.valueOf(parameters.getOrDefault(key, fallback))).intValueExact();
      if (value < min || value > max) throw new ArithmeticException();
      return value;
    } catch (NumberFormatException | ArithmeticException e) { throw new IllegalArgumentException(key + " must be an integer between " + min + " and " + max); }
  }
  private static String endpoint(Object value) {
    if (!(value instanceof String text)) throw new IllegalArgumentException("Chemical API endpoint must be a URL string");
    var uri = URI.create(text);
    boolean local = Set.of("localhost", "127.0.0.1", "[::1]").contains(String.valueOf(uri.getHost()));
    if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
        || !("https".equals(uri.getScheme()) || local && "http".equals(uri.getScheme())))
      throw new IllegalArgumentException("Chemical API endpoint must use HTTPS, or loopback HTTP for testing");
    return text.replaceAll("/+$", "");
  }
  private record Bridge(ConfigurationRequest request, PubChemClient pubchem, ChebiClient chebi,
      int limit, boolean gif, Path directory) {}
}
