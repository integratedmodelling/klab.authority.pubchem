package org.integratedmodelling.klab.authority.pubchem;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.*;

/** Documented PUG REST compound operations; no heuristic family-to-compound conversion. */
final class PubChemClient {
  static final String PROPERTIES = "Title,IUPACName,MolecularFormula,MolecularWeight,InChI,InChIKey";
  private final ChemicalHttpClient http;
  private final String endpoint;
  private final String autocompleteEndpoint;
  private final Duration timeout;
  PubChemClient(ChemicalHttpClient http, String endpoint, String autocompleteEndpoint, Duration timeout) {
    this.http = http; this.endpoint = endpoint; this.autocompleteEndpoint = autocompleteEndpoint; this.timeout = timeout;
  }
  String propertyUrl(String cid) { return endpoint + "/compound/cid/" + cid + "/property/" + PROPERTIES + "/JSON"; }
  String depictionUrl(String cid) { return endpoint + "/compound/cid/" + cid + "/PNG?image_size=large"; }
  JsonNode properties(String cid) {
    var response = http.json(propertyUrl(cid), timeout);
    if (response == null) throw new IllegalArgumentException("Unknown PubChem CID: " + cid);
    var items = response.path("PropertyTable").path("Properties");
    if (!items.isArray() || items.size() != 1 || !cid.equals(items.get(0).path("CID").asText())
        || items.get(0).path("Title").asText().isBlank() || items.get(0).path("InChI").asText().isBlank())
      throw new IllegalStateException("PubChem omitted or mismatched compound properties");
    return items.get(0);
  }
  List<String> cids(String namespace, String input) {
    String url = endpoint + "/compound/" + namespace + "/cids/JSON?" + namespace + "=" + ChemicalHttpClient.encode(input);
    if ("name".equals(namespace)) url += "&name_type=complete";
    var response = http.json(url, timeout);
    if (response == null) return List.of();
    var array = response.path("IdentifierList").path("CID");
    if (!array.isArray()) throw new IllegalStateException("PubChem omitted CID list");
    var ids = new LinkedHashSet<String>();
    for (var value : array) {
      String id = value.asText();
      if (!id.matches("[1-9][0-9]*")) throw new IllegalStateException("Invalid PubChem CID");
      ids.add(id);
    }
    return List.copyOf(ids);
  }
  List<String> chebiReferences(String cid) {
    var response = http.json(endpoint + "/compound/cid/" + cid + "/synonyms/JSON", timeout);
    if (response == null) throw new IllegalStateException("PubChem compound synonyms unavailable");
    var info = response.path("InformationList").path("Information");
    if (!info.isArray() || info.size() != 1 || !cid.equals(info.get(0).path("CID").asText())
        || !info.get(0).path("Synonym").isArray()) throw new IllegalStateException("Invalid PubChem synonyms");
    var ids = new LinkedHashSet<String>();
    for (var synonym : info.get(0).path("Synonym"))
      if (synonym.asText().matches("CHEBI:[1-9][0-9]*")) ids.add(synonym.asText());
    if (ids.size() > 32) throw new IllegalStateException("Too many ChEBI references for one compound");
    return List.copyOf(ids);
  }
  /** Bounded PubChem fuzzy/prefix suggestions; every suggestion still resolves by complete name. */
  List<String> suggestions(String input, int limit) {
    var response = http.json(autocompleteEndpoint + "/compound/" + ChemicalHttpClient.encode(input)
        + "/json?limit=" + limit, timeout);
    if (response == null) throw new IllegalStateException("PubChem autocomplete unavailable");
    if (!response.path("status").path("code").canConvertToInt()
        || response.path("status").path("code").asInt() != 0)
      throw new IllegalStateException("PubChem autocomplete failed");
    var terms = response.path("dictionary_terms").path("compound");
    if (terms.isMissingNode() && response.path("total").asInt(-1) == 0) return List.of();
    if (!terms.isArray()) throw new IllegalStateException("PubChem autocomplete omitted compound terms");
    var names = new LinkedHashSet<String>();
    for (var term : terms) {
      if (!term.isTextual() || term.asText().isBlank()) throw new IllegalStateException("Invalid autocomplete term");
      names.add(term.asText());
      if (names.size() >= limit) break;
    }
    return List.copyOf(names);
  }
  ChemicalHttpClient.Resource depiction(String cid) {
    return http.read(depictionUrl(cid), timeout, "image/png");
  }
}
