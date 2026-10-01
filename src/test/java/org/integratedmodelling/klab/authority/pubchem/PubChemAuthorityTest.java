package org.integratedmodelling.klab.authority.pubchem;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import org.integratedmodelling.klab.api.services.Authority;
import org.integratedmodelling.klab.api.services.Authority.ConfigurationRequest;
import org.integratedmodelling.klab.api.services.runtime.Notification;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PubChemAuthorityTest {
  private HttpServer server;
  private PubChemAuthority authority;
  private String endpoint;
  private final Map<String, byte[]> responses = new ConcurrentHashMap<>();
  private final Map<String, Integer> statuses = new ConcurrentHashMap<>();
  private final Map<String, String> types = new ConcurrentHashMap<>();
  private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
  @TempDir Path documentation;
  private static final String INCHI = "InChI=1S/H2O/h1H2";
  private static final String PROPERTIES = "/compound/cid/962/property/" + PubChemClient.PROPERTIES + "/JSON";

  @BeforeEach void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      String path = exchange.getRequestURI().getPath();
      String query = exchange.getRequestURI().getRawQuery();
      String key = path;
      if (path.endsWith("/terms") || path.endsWith("/parents")) key += "?" + query;
      if (path.endsWith("/cids/JSON")) key += "?" + query;
      requests.add(exchange.getRequestURI().toString());
      byte[] body = responses.getOrDefault(key, "{}".getBytes(StandardCharsets.UTF_8));
      exchange.getResponseHeaders().set("Content-Type", types.getOrDefault(key, "application/json"));
      exchange.sendResponseHeaders(statuses.getOrDefault(key, responses.containsKey(key) ? 200 : 404), body.length);
      try (var output = exchange.getResponseBody()) { output.write(body); }
    });
    server.start();
    endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
    authority = new PubChemAuthority();
    put(PROPERTIES, """
        {"PropertyTable":{"Properties":[{"CID":962,"Title":"Water","InChI":"InChI=1S/H2O/h1H2",
        "InChIKey":"XLYOFNOQVPJJNP-UHFFFAOYSA-N","MolecularFormula":"H2O","IUPACName":"oxidane","MolecularWeight":"18.015"}]}}
        """);
    put("/compound/cid/962/synonyms/JSON", "{\"InformationList\":{\"Information\":[{\"CID\":962,\"Synonym\":[\"CHEBI:15377\",\"CHEBI:99\"]}]}}");
    term("CHEBI:15377", "water", INCHI, List.of("CHEBI:24431"));
    // A deposit synonym is not sufficient proof of structural equivalence.
    term("CHEBI:99", "different structure", "InChI=1S/He", List.of("CHEBI:123"));
    term("CHEBI:24632", "hydrocarbon", "", List.of("CHEBI:33245"));
    term("CHEBI:33245", "organic fundamental parent", "", List.of("CHEBI:24431"));
    term("CHEBI:24431", "chemical entity", "", List.of());
    put("/compound/inchi/cids/JSON?inchi=" + ChemicalHttpClient.encode(INCHI), "{\"IdentifierList\":{\"CID\":[962]}}");
    put("/compound/name/cids/JSON?name=water&name_type=complete", "{\"IdentifierList\":{\"CID\":[962]}}");
    put("/search", "{\"response\":{\"numFound\":1,\"docs\":[{\"obo_id\":\"CHEBI:24632\",\"ontology_name\":\"chebi\",\"type\":\"class\",\"label\":\"hydrocarbon\",\"exact_synonyms\":[\"hydrocarbons\"]}]}}");
    var png = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB), "png", png);
    responses.put("/compound/cid/962/PNG", png.toByteArray());
    types.put("/compound/cid/962/PNG", "image/png");
  }
  @AfterEach void stop() { server.stop(0); }
  private void put(String path, String body) { responses.put(path, body.getBytes(StandardCharsets.UTF_8)); }
  private String configure() { return authority.configure(request("CHEM", "chemistry:ChemicalIdentity", Map.of())); }
  private ConfigurationRequest request(String name, String root, Map<String, Object> extra) {
    var parameters = new HashMap<String, Object>(Map.of("urn", PubChemAuthority.URN,
        "endpoint", endpoint, "chebiEndpoint", endpoint, "documentationDirectory", documentation.toString()));
    parameters.putAll(extra);
    return new ConfigurationRequest("worldview", name, root, parameters);
  }
  private String termNode(String id, String label, String inchi) {
    return "{\"obo_id\":\"" + id + "\",\"ontology_name\":\"chebi\",\"label\":\"" + label
        + "\",\"is_obsolete\":false,\"is_defining_ontology\":true,\"description\":[\"Definition\"],\"annotation\":{\"inchi_string\":[\"" + inchi + "\"]}}";
  }
  private String termKey(String id) { return "/ontologies/chebi/terms?iri=" + ChemicalHttpClient.encode("http://purl.obolibrary.org/obo/" + id.replace(':', '_')); }
  private String parentKey(String id) { return "/ontologies/chebi/parents?id=" + ChemicalHttpClient.encode(id) + "&size=100"; }
  private void term(String id, String label, String inchi, List<String> parents) {
    put(termKey(id), "{\"_embedded\":{\"terms\":[" + termNode(id, label, inchi) + "]}}");
    put(parentKey(id), "{\"_embedded\":{\"terms\":[" + String.join(",", parents.stream().map(p -> termNode(p, "Parent", "")).toList()) + "]},\"page\":{\"totalPages\":1}}");
  }
  private void clean(Authority.Identity identity) {
    assertTrue(identity.getNotifications().isEmpty(), () -> identity.getDescription());
  }
  private void error(Authority.Identity identity) {
    assertTrue(identity.getNotifications().stream().anyMatch(n -> n.getLevel() == Notification.Level.Error));
    assertNull(identity.getConceptName());
  }

  @Test void discoveryContractAndConfigurationIsolation() {
    assertEquals(PubChemAuthority.URN, authority.getUrn());
    assertEquals(authority.getUrn(), PubChemAuthority.class.getAnnotation(org.integratedmodelling.klab.api.services.reasoner.Authority.class).urn());
    String first = configure();
    String second = authority.configure(request("OTHER", "other:Root", Map.of()));
    assertNotEquals(first, second);
    var a = authority.resolveIdentity(first, "CHEBI:24431");
    var b = authority.resolveIdentity(second, "CHEBI:24431");
    assertEquals("CHEM", a.getAuthorityName());
    assertEquals("chemistry:ChemicalIdentity", a.getBaseIdentity());
    assertEquals("other:Root", b.getBaseIdentity());
    authority.releaseConfiguration(first);
    assertThrows(IllegalArgumentException.class, () -> authority.resolveIdentity(first, "962"));
    clean(authority.resolveIdentity(second, "CHEBI:24431"));
    assertTrue(authority.getCachePolicy().identitySeconds() < Long.MAX_VALUE);
  }

  @Test void moleculesCanonicalizeCodesAndOnlyUseVerifiedStructuralParents() {
    String bridge = configure();
    var molecule = authority.resolveIdentity(bridge, "962");
    clean(molecule);
    assertEquals("CID:962", molecule.getId());
    assertEquals("CID_962", molecule.getConceptName());
    assertEquals(List.of("CHEBI:24431"), molecule.getParentIds());
    assertNull(molecule.getBaseIdentity());
    assertEquals("CHEM:CID_962", molecule.getLocator());
    for (String alias : List.of("CID_962", "CHEBI:15377", INCHI)) {
      var result = authority.resolveIdentity(bridge, alias);
      clean(result);
      assertEquals(molecule.getId(), result.getId());
      assertEquals(molecule.getParentIds(), result.getParentIds());
    }
    assertFalse(requests.stream().anyMatch(p -> p.startsWith(parentKey("CHEBI:99"))));
  }

  @Test void familiesRetainOntologyIdentityAndResolvableParentsWithoutDepictions() {
    String bridge = configure();
    var family = authority.resolveIdentity(bridge, "CHEBI_24632");
    clean(family);
    assertEquals("CHEBI:24632", family.getId());
    assertEquals(List.of("CHEBI:33245"), family.getParentIds());
    clean(authority.resolveIdentity(bridge, family.getParentIds().get(0)));
    assertFalse(family.getDocumentation().containsKey("image/gif"));
    assertFalse(family.getDocumentation().containsKey("image/png"));
    assertTrue(family.getDescription().startsWith("# hydrocarbon\n\n"));
    assertThrows(UnsupportedOperationException.class, () -> family.getDocumentation().clear());
  }

  @Test void documentationIncludesMetadataMarkdownAndActualGifBytes() throws Exception {
    var identity = authority.resolveIdentity(configure(), "962");
    clean(identity);
    String markdown;
    try (var stream = identity.getDocumentation().get("text/markdown").openStream()) {
      markdown = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertTrue(markdown.contains("MolecularFormula"));
    assertTrue(markdown.contains("ChEBI"));
    assertTrue(markdown.contains("oxidane"));
    assertTrue(identity.getDescription().startsWith("# Water\n\n"));
    assertEquals(markdown, identity.getDescription());
    assertTrue(identity.getDocumentation().get("text/markdown").toString().contains(documentation.getFileName().toString()));
    try (var stream = identity.getDocumentation().get("image/gif").openStream()) {
      byte[] bytes = stream.readAllBytes();
      assertEquals("GIF", new String(bytes, 0, 3, StandardCharsets.US_ASCII));
      assertNotNull(ImageIO.read(new java.io.ByteArrayInputStream(bytes)));
    }
  }

  @Test void optionalDepictionFailuresWarnWithoutRejectingIdentity() {
    types.put("/compound/cid/962/PNG", "text/html");
    var identity = authority.resolveIdentity(configure(), "962");
    assertEquals("CID:962", identity.getId());
    assertFalse(identity.getDocumentation().containsKey("image/gif"));
    assertTrue(identity.getNotifications().stream().allMatch(n -> n.getLevel() == Notification.Level.Warning));
    assertFalse(identity.getNotifications().isEmpty());
  }

  @Test void disabledGifKeepsConfiguredDocumentationDirectory() {
    var bridge = authority.configure(request("CHEM", "chemistry:Root", Map.of("gifDepictions", false)));
    var identity = authority.resolveIdentity(bridge, "962");
    clean(identity);
    assertFalse(requests.stream().anyMatch(r -> r.contains("/PNG")));
    assertFalse(identity.getDocumentation().containsKey("image/gif"));
    assertTrue(identity.getDocumentation().get("text/markdown").toString().contains(documentation.getFileName().toString()));
  }

  @Test void markdownAndDocumentationSurviveSharedDtoJsonRoundTrip() throws Exception {
    var identity = authority.resolveIdentity(configure(), "CHEBI:24632");
    clean(identity);
    var mapper = new ObjectMapper();
    var dto = mapper.readValue(mapper.writeValueAsBytes(identity),
        org.integratedmodelling.klab.api.services.resources.objects.AuthorityIdentity.class);
    assertEquals(identity.getDescription(), dto.getDescription());
    assertEquals(identity.getDocumentation(), dto.getDocumentation());
    assertEquals(identity.getParentIds(), dto.getParentIds());
    assertEquals(identity.getLocator(), dto.getLocator());
    assertTrue(dto.getDescription().startsWith("# hydrocarbon\n\n"));
  }

  @Test void codeLookupDoesNotSilentlyReconcileNamesOrAmbiguousStructures() {
    String bridge = configure();
    error(authority.resolveIdentity(bridge, "water"));
    put("/compound/inchi/cids/JSON?inchi=" + ChemicalHttpClient.encode(INCHI), "{\"IdentifierList\":{\"CID\":[962,963]}}");
    error(authority.resolveIdentity(bridge, INCHI));
  }

  @Test void searchFiltersPreserveCanonicalNamespaceAndCandidateResolvability() {
    String bridge = configure();
    var families = authority.search("hydrocarbons", "CHEBI", bridge);
    assertEquals(1, families.size());
    assertEquals("CHEBI:24632", families.get(0).getId());
    clean(authority.resolveIdentity(bridge, families.get(0).getId()));
    var molecule = authority.subAuthority("COMPOUND").search("water", null, bridge).get(0);
    assertEquals("CHEM", molecule.getAuthorityName());
    assertEquals("CID:962", molecule.getId());
    assertThrows(IllegalArgumentException.class, () -> authority.search("water", "INVALID", bridge));
  }

  @Test void reconciliationChecksExactNamesAndDoesNotTrustProviderRanking() {
    String bridge = configure();
    clean(authority.reconcile(bridge, Map.of("name", "hydrocarbons", "catalog", "CHEBI")));
    error(authority.reconcile(bridge, Map.of("name", "unrelated", "catalog", "CHEBI")));
    put("/search", "{\"response\":{\"numFound\":101,\"docs\":[{\"obo_id\":\"CHEBI:24632\",\"ontology_name\":\"chebi\",\"type\":\"class\",\"label\":\"hydrocarbon\"}]}}");
    error(authority.reconcile(bridge, Map.of("name", "hydrocarbon", "catalog", "CHEBI")));
    put("/compound/name/cids/JSON?name=water&name_type=complete", "{\"IdentifierList\":{\"CID\":[962,963]}}");
    error(authority.reconcile(bridge, Map.of("name", "water", "catalog", "COMPOUND")));
  }

  @Test void duplicateMoleculeCandidatesCollapseAfterVerifiedChebiMapping() {
    put("/search", "{\"response\":{\"numFound\":1,\"docs\":[{\"obo_id\":\"CHEBI:15377\",\"ontology_name\":\"chebi\",\"type\":\"class\",\"label\":\"water\"}]}}");
    String bridge = configure();
    var result = authority.search("water", null, bridge);
    assertEquals(1, result.size());
    assertEquals("CID:962", result.get(0).getId());
    assertEquals("CID:962", authority.reconcile(bridge, Map.of("name", "water")).getId());
  }

  @Test void partialCompoundNamesUseAutocompleteAndRetainMarkdown() {
    put("/autocomplete/compound/wat/json", "{\"status\":{\"code\":0},\"total\":1,\"dictionary_terms\":{\"compound\":[\"water\"]}}");
    String bridge = configure();
    var result = authority.search("wat", "COMPOUND", bridge);
    assertEquals(1, result.size());
    assertEquals("CID:962", result.get(0).getId());
    assertEquals(0.75f, result.get(0).getScore());
    assertTrue(result.get(0).getDescription().startsWith("# Water\n\n"));
    assertTrue(result.get(0).getDocumentation().containsKey("text/markdown"));
    assertTrue(requests.stream().anyMatch(r -> r.contains("/autocomplete/compound/wat/")));
    assertFalse(requests.stream().anyMatch(r -> r.contains("/PNG") || r.contains("/parents") || r.contains("/synonyms")));
    clean(authority.resolveIdentity(bridge, result.get(0).getId()));
    error(authority.resolveIdentity(bridge, "wat"));
    error(authority.reconcile(bridge, Map.of("name", "wat", "catalog", "COMPOUND")));
  }

  @Test void exactCompoundNamesTakePriorityAndSearchDefersHierarchyAndGifWork() {
    String bridge = configure();
    var results = authority.search("water", "COMPOUND", bridge);
    assertEquals("CID:962", results.getFirst().getId());
    assertEquals(1f, results.getFirst().getScore());
    assertNull(results.getFirst().getBaseIdentity());
    assertTrue(results.getFirst().getParentIds().isEmpty());
    assertFalse(requests.stream().anyMatch(r -> r.contains("autocomplete") || r.contains("/PNG") || r.contains("/parents") || r.contains("/synonyms")));
    clean(authority.resolveIdentity(bridge, results.getFirst().getId()));
    assertTrue(requests.stream().anyMatch(r -> r.contains("/parents")));
  }

  @Test void emptyAutocompleteIsNoMatchButMalformedAutocompleteIsAFailure() {
    String bridge = configure();
    put("/autocomplete/compound/nothing/json", "{\"status\":{\"code\":0},\"total\":0}");
    assertTrue(authority.search("nothing", "COMPOUND", bridge).isEmpty());
    put("/autocomplete/compound/nothing/json", "{\"status\":{\"code\":0}}");
    assertThrows(IllegalStateException.class, () -> authority.search("nothing", "COMPOUND", bridge));
  }

  @Test void invalidHierarchyAndObsoleteTermsRejectMaterialization() {
    String bridge = configure();
    term("CHEBI:24632", "hydrocarbon", "", List.of("CHEBI:24632"));
    error(authority.resolveIdentity(bridge, "CHEBI:24632"));
    term("CHEBI:24632", "hydrocarbon", "", List.of("CHEBI:24431"));
    put(termKey("CHEBI:24632"), new String(responses.get(termKey("CHEBI:24632")), StandardCharsets.UTF_8).replace("\"is_obsolete\":false", "\"is_obsolete\":true"));
    error(authority.resolveIdentity(configure(), "CHEBI:24632"));
  }

  @Test void indirectCyclesAndMissingAncestorsFailBeforeMaterialization() {
    term("CHEBI:33245", "parent", "", List.of("CHEBI:24632"));
    error(authority.resolveIdentity(configure(), "CHEBI:24632"));
    term("CHEBI:33245", "parent", "", List.of("CHEBI:88888"));
    error(authority.resolveIdentity(configure(), "CHEBI:24632"));
  }

  @Test void upstreamFailuresAreErrorsRatherThanEmptySearches() {
    String bridge = configure();
    put("/search", "{\"response\":{}}");
    assertThrows(IllegalStateException.class, () -> authority.search("hydrocarbons", "CHEBI", bridge));
    error(authority.resolveIdentity(bridge, "CID:888"));
    put(PROPERTIES, "<html>maintenance</html>");
    error(authority.resolveIdentity(bridge, "962"));
    statuses.put(PROPERTIES, 202);
    error(authority.resolveIdentity(bridge, "962"));
  }

  @Test void transportBoundsBodiesAndRetriesTransientFailures() {
    String url = endpoint + "/large";
    responses.put("/large", new byte[4 * 1024 * 1024 + 1]);
    assertThrows(IllegalStateException.class, () -> new ChemicalHttpClient().json(url, Duration.ofSeconds(10)));
    put("/retry", "{}");
    statuses.put("/retry", 503);
    assertThrows(IllegalStateException.class, () -> new ChemicalHttpClient().json(endpoint + "/retry", Duration.ofSeconds(10)));
    assertEquals(3, requests.stream().filter(r -> r.equals("/retry")).count());
  }

  @Test void configurationRejectsInvalidParameters() {
    assertThrows(IllegalArgumentException.class, () -> authority.configure(request("CHEM", "chemistry:Root", Map.of("bogus", 1))));
    assertThrows(IllegalArgumentException.class, () -> authority.configure(request("CHEM", "chemistry:Root", Map.of("searchLimit", 1.5))));
    assertThrows(IllegalArgumentException.class, () -> authority.configure(request("CHEM", "chemistry:Root", Map.of("endpoint", "http://example.org"))));
    assertThrows(IllegalArgumentException.class, () -> authority.configure(request("CHEM", "chemistry:Root", Map.of("gifDepictions", "true"))));
  }
}
