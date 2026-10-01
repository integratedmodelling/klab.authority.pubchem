# k.LAB chemical identity authority

`klab.authority.pubchem` is an embeddable component implementing the current
`org.integratedmodelling.klab.api.services.Authority` contract. PubChem supplies molecular
identities and structure depictions; ChEBI supplies compound families and other chemical ontology
entities through EMBL-EBI's maintained OLS4 service. Provider URN: `klab.authority.pubchem`;
authority version: `1.0.0`; component version: `1.0.0-SNAPSHOT`.

## Provider organization and sources

- `PubChemAuthority`: configuration lifecycle, canonical identities, search, reconciliation,
  worldview roots and capabilities.
- `PubChemClient`: documented PUG REST CID lookup, compound properties, synonyms, InChI/InChIKey
  translation, complete-name lookup and PNG depictions.
- `ChebiClient`: OLS4 term lookup, direct structural parents, hierarchy validation and lexical search.
- `ChemicalHttpClient`: shared request pacing, bounded bodies, complete HTTP deadlines and retries.
- `ChemicalIdentity`: immutable shared-contract identity data.
- `ChemicalDocumentation`: Markdown containing source metadata and links, and optional GIF generation.

The API review and live development probes were performed on 2026-10-01. Primary references:

- [PubChem PUG REST specification](https://pubchem.ncbi.nlm.nih.gov/docs/pug-rest)
- [PUG REST tutorial](https://pubchem.ncbi.nlm.nih.gov/docs/pug-rest-tutorial)
- [PubChem classification browser](https://pubchem.ncbi.nlm.nih.gov/docs/classification-browser)
- [PubChem imaging services](https://pubchem.ncbi.nlm.nih.gov/docs/imaging-services)
- [EMBL-EBI OLS4 API](https://www.ebi.ac.uk/ols4/api-docs)
- [OLS API help and direct parents](https://www.ebi.ac.uk/ols4/ols3help)
- [Maintained OLS service](https://www.ebi.ac.uk/ols4/about)

PUG REST classification-node lookup returns lists of annotated record IDs. Such membership does not
provide the ChEBI definitions and typed ontology edges required to materialize chemical families.
OLS4 supplies these vocabulary records; this implementation uses its direct `parents` operation,
which exposes structural superclass links, rather than the broader `hierarchicalParents` operation.
`has_role`, `part_of`, conjugate-acid/base, tautomer, and similar relationships are not converted into
superclasses. PUG-View reports, PubChem RDF classifications, and classification HNIDs are possible
future enrichment sources; none is treated as a chemical identity code in this version.

## Worldview binding

```kwv
identity ChemicalIdentity
    requires authority CHEM {
        urn: "klab.authority.pubchem",
        searchLimit: 10,
        gifDepictions: true
    }
;
```

Each call to `configure()` creates an independent opaque handle with its own worldview name, local
authority name, root and clients. Configuration validates parameters without requiring a network
probe. `releaseConfiguration()` removes the handle. Unknown/released handles throw an exception.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `urn` | Required | Exactly `klab.authority.pubchem`. |
| `endpoint` | `https://pubchem.ncbi.nlm.nih.gov/rest/pug` | PubChem PUG REST base URL. |
| `autocompleteEndpoint` | Sibling `../autocomplete` URL of `endpoint` | PubChem REST partial-name/autocomplete service. Defaults to `https://pubchem.ncbi.nlm.nih.gov/rest/autocomplete`; can be overridden for a proxy or fixture server. |
| `chebiEndpoint` | `https://www.ebi.ac.uk/ols4/api` | OLS4 API base URL serving ChEBI. |
| `timeoutSeconds` | `20` | Complete deadline for each HTTP operation, including pacing, retries and body; integer 1–120. |
| `searchLimit` | `10` | Maximum returned candidates; integer 1–50. |
| `gifDepictions` | `true` | Fetch PNG and convert to GIF for molecular documentation. |
| `documentationDirectory` | JVM temporary directory / `klab.authority.pubchem/documentation` | Absolute host path for content-addressed Markdown and GIF files. Set a durable service-owned directory in production. |

Endpoint overrides require HTTPS; HTTP is permitted on loopback for fixture tests. Unknown
parameters, fractional limits and string booleans are rejected. No API key is required or supported.

## Identity and canonicalization

The provider resolves codes; human names require `search()` or explicit `reconcile()`.

| Input | Resolution |
| --- | --- |
| `962`, `CID:962`, `CID_962` | PubChem water compound, canonical ID `CID:962`, concept name `CID_962`. |
| `CHEBI:24632`, `CHEBI_24632` | Hydrocarbon family, canonical ID `CHEBI:24632`, concept name `CHEBI_24632`. |
| `InChI=1S/H2O/h1H2` | Unique PubChem structure lookup, canonical CID. |
| `XLYOFNOQVPJJNP-UHFFFAOYSA-N` | Unique PubChem InChIKey lookup, canonical CID. |
| `CHEBI:15377` | Water ontology term; maps to `CID:962` after unique InChI lookup and verification. |

Use colon-free aliases in semantic source: `CHEM:CID_962`, `CHEM:CHEBI_24632` or `CHEM:962`.
Canonical locators use this same grammar-safe spelling. API codes preserve their source namespace
with a colon; every search result code resolves in the same bridge. InChI strings containing
slashes and special characters are passed as encoded query parameters, never inserted into a path.
The legacy cactus.nih/IUPAC authority is not called. Formula strings are not unique chemical
identifiers and are not silently accepted as molecule codes.

ChEBI terms with no molecular InChI remain ontology identities. This covers families such as
hydrocarbons, broader classes, ions without a mapped structure, and recognized mixtures and roles
represented in ChEBI. The `CHEBI` search catalog means the ChEBI vocabulary, not exclusively families.
A molecular ChEBI record maps to a CID only when its InChI matches a unique PubChem CID and the
returned compound's InChI agrees. Non-unique or absent PubChem mappings retain the ChEBI identity.
Two unmapped vocabulary records are never merged merely because their labels agree.

PubChem synonym cross-references are independently verified against the ChEBI term's InChI before
contributing structural parents. A CID inherits the direct parents of its verified ChEBI molecular
terms; those equivalent molecular terms do not become superclasses of the CID itself. Unverified
cross-references do not contribute parents. A CID with no verified mapping attaches to the worldview
root. The provider does not infer a class from a molecular formula or from a name.

ChEBI identities preserve all immediate structural parents. An identity without parents supplies
the configured root as `baseIdentity`; other identities inherit through their parents. The complete
ChEBI structural ancestry is validated before returning an identity: missing, obsolete or imported
terms, mismatched IDs, cycles, more than 64 levels or 256 distinct nodes, and parent pagination beyond
100 parents fail explicitly. Parents resolve through the same bridge, regardless of search filter.

## Search and reconciliation

```java
var authority = new PubChemAuthority();
String bridge = authority.configure(new Authority.ConfigurationRequest(
    "example.worldview", "CHEM", "chemistry:ChemicalIdentity",
    Map.of("urn", PubChemAuthority.URN)));

var candidates = authority.search("hydrocarbons", null, bridge);
var family = authority.resolveIdentity(bridge, "CHEBI_24632");
var selected = authority.reconcile(bridge, Map.of("name", "hydrocarbons", "catalog", "CHEBI"));
```

Search accepts ordinary words and codes. It combines PubChem complete-name matches with ChEBI
label/synonym matches. When PubChem finds no complete-name match, its documented
[autocomplete service](https://pubchem.ncbi.nlm.nih.gov/docs/autocomplete) supplies up to five partial
or fuzzy name suggestions (bounded also by `searchLimit`); each suggested name is looked up by
complete name to obtain real CIDs. For example, `wat` can suggest water. Exact PubChem names take
priority and do not trigger autocomplete. Results canonicalize molecular terms, deduplicate by
canonical ID and return up to the configured limit. Scores are relevance tiers: 1 for direct codes
and complete PubChem name matches, 0.75 for autocomplete, and 0.5 for ChEBI lexical candidates.
The first PubChem candidates precede ontology-only candidates. Results are candidates for user selection,
not an assertion that an arbitrary name denotes one molecule. Empty successful searches mean no
matches; HTTP/schema/structure-mapping failures throw, rather than masquerading as empty results.
Search candidates carry required Markdown descriptions and documentation URLs, but ancestry
validation and GIF generation are deferred to `resolveIdentity()` or exact reconciliation after
selection. Their omitted parent/base fields do not assert a parentless identity. The shared Reasoner
cache already keeps search results separate from identity lookup, so a candidate never bypasses
the stronger materialization checks.

`COMPOUND` and `CHEBI` are advertised search filters. `CHEM.CHEBI:<code>` resolves through the
base bridge and retains its canonical namespace; explicit provider views share bridge state and
can still resolve parents from either vocabulary. Codes supplied as search queries resolve directly.

Reconciliation accepts `name` and optional `catalog` (`COMPOUND` or `CHEBI`). PubChem uses complete
name matching. ChEBI names must agree exactly, ignoring case, with the label or an explicitly exact
synonym; related synonyms and provider ranking are insufficient. The OLS service was observed to
return broader terms even when asked for an exact search, so exactness is checked locally. The
complete returned candidate set must fit 100 rows; larger sets require code selection. After
verified molecule deduplication, exactly one identity is required. Ambiguity, truncation, no match
or transport failure returns an identity with an error notification. Implicit fuzzy lookup is disabled.

## Metadata, documentation and depictions

Resolved identities supply canonical IDs/names, local namespace and locator, label, Markdown
description, structural parents, root when appropriate, score and diagnostics. Source properties
(formula, IUPAC name, InChI, InChIKey, molecular weight) and ChEBI definitions, annotations, synonyms,
cross-references and ontology provenance are preserved in the Markdown/description. They are not
added as undocumented plug-in-only fields that would be lost by the core DTO/cache.

`getDocumentation()` returns URLs keyed by actual media type:

- `text/markdown`: generated content-addressed Markdown with chemical metadata and source links.
- `application/json`: the relevant upstream properties or ChEBI term response.
- `text/html`: the source record page.
- `image/png`: documented PUG REST molecular depiction URL.
- `image/gif`: optional local file containing actual GIF bytes converted from the fetched PNG.

PUG REST documents PNG depictions, not a GIF endpoint. GIF content is generated with Java ImageIO
after validating image type and dimensions; there is no guessed `/GIF` URL. Families without a
resolved molecular structure have no molecule depiction. A missing PNG omits the GIF. Depiction
transport/conversion failures add a warning without invalidating the chemical identity; failure to
write required Markdown is an error. Local files outlive bridge release. The current Reasoner
documentation controller publishes provider-local files through its authenticated content endpoint
while retaining upstream URLs. Production must retain the documentation directory at least as long
as Reasoner identity caches, and arrange its cleanup separately; JVM temporary files can be removed
by the host.

## Transport, caching and limitations

Every HTTP body is bounded to 4 MiB. A process-wide pacing gate permits at most four request starts
per second across provider instances, below PubChem's five-per-second application limit. It also
paces OLS requests. Coordination across processes/other PubChem applications remains deployment work.
429 and 503 are retried at most twice within the same HTTP deadline; numeric Retry-After is honored
up to 30 seconds, while date-form values use a one-second delay. Interruptions cancel the request and
preserve interrupt state. 202/asynchronous results are reported as incomplete, not accepted as IDs.
404 compound name/structure searches mean no match; vocabulary lookup and hierarchy failures are
errors. Operations involving multiple records may exceed one HTTP deadline in total.
The client uses HTTP/1.1: during live testing OLS4 closed the JDK's HTTP/2 connections with GOAWAY,
while HTTP/1.1 served the same requests successfully.

Reasoner persistence uses the core cache and DTOs. Policy revision `pubchem-chebi-2` retains identities
for one day and search/reconciliation for five minutes. PubChem and hosted ChEBI are mutable; neither
is advertised as immutable or release-pinned. Each ChEBI client retains at most 512 raw lookup/parent
responses for five minutes to avoid repeated hierarchy calls. Cache expiry does not rewrite already
materialized Reasoner axioms: reload affected bindings to adopt upstream changes.

No Reasoner internals or global authority registration are changed by this component. Full-stack
Resources discovery/transfer, worldview ingestion, deployment and ontology reload remain integration
work. Chemical-distance evaluation, typed non-structural edges, substances/SIDs, biological PubChem
record types, classification-node identities, release pinning and offline operation are not supplied.
If a retained offline vocabulary is required, a classpath ChEBI OWL distribution and indexed ontology
adapter can replace `ChebiClient`; the Reasoner dependency can supply OWL2 support. The current
maintained OLS4 service makes that dependency unnecessary for this initial implementation.

## Build and tests

Use JDK 21:

```shell
mvn test
mvn package
```

The packaging plug-in produces the standard component archive under `target/`. Host dependencies
are `provided`; packaging targets Reasoner and Resources servers. The buildnumber plug-in records
the current Git revision, or uses `unversioned` outside a Git checkout. No remote publication occurs.

Ordinary tests use a loopback HTTP server and synthetic molecular PNGs. They cover discovery,
configuration isolation/release, CID/InChI/ChEBI canonicalization, verified structural links, families,
roots, resolvable search candidates, reconciliation ambiguity/exactness, hierarchy failures,
Markdown metadata, valid GIF bytes, optional depiction failures, malformed/asynchronous responses,
response size limits and transient retries.
The shared core DTO JSON round-trip test verifies that both the Markdown description and its
documentation resources survive serialization. Live tests are explicitly opt-in:

```shell
mvn "-Dpubchem.live=true" "-Dtest=PubChemLiveTest" test
```

The live test checks official water CID/ChEBI mapping, hydrocarbon family lookup/search/reconciliation
and GIF output. On 2026-10-01, all 16 fixture tests, the separately enabled live smoke test, and
component packaging passed. The ordinary build skips the live test.
Live results reflect mutable upstream data and are not fixtures.

The search follow-up on 2026-10-01 passed 19 fixture tests and a live test covering default-limit
`water` search and partial `wat` compound search, then rebuilt the component archive. Before the
change, a live ten-candidate `water` search spent approximately 49 seconds validating full ancestry;
search now defers that work until identity selection. This is a latency finding, not confirmation
of the cause of any particular front-end failure. Reload the updated component in active services
to adopt the changed search behavior.
