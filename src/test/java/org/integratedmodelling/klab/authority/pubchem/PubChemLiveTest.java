package org.integratedmodelling.klab.authority.pubchem;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.integratedmodelling.klab.api.services.Authority;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Explicit opt-in smoke test; ordinary builds use local fixtures only. */
@EnabledIfSystemProperty(named = "pubchem.live", matches = "true")
class PubChemLiveTest {
  @Test void officialCompoundAndChemicalFamilyEndpoints() throws Exception {
    var authority = new PubChemAuthority();
    String bridge = authority.configure(new Authority.ConfigurationRequest("test", "CHEM",
        "chemistry:Identity", Map.of("urn", PubChemAuthority.URN, "searchLimit", 3)));
    try {
      var water = authority.resolveIdentity(bridge, "962");
      assertTrue(water.getNotifications().isEmpty(), water.getDescription());
      assertEquals("CID:962", water.getId());
      assertEquals("CID:962", authority.resolveIdentity(bridge, "CHEBI:15377").getId());
      assertTrue(water.getDocumentation().containsKey("image/gif"));
      try (var input = water.getDocumentation().get("image/gif").openStream()) {
        assertNotNull(javax.imageio.ImageIO.read(input));
      }
      var family = authority.resolveIdentity(bridge, "CHEBI:24632");
      assertTrue(family.getNotifications().isEmpty(), family.getDescription());
      assertEquals("CHEBI:24632", family.getId());
      assertFalse(family.getParentIds().isEmpty());
      assertTrue(authority.search("hydrocarbons", "CHEBI", bridge).stream()
          .anyMatch(i -> i.getId().equals("CHEBI:24632")));
      var reconciled = authority.reconcile(bridge, Map.of("name", "hydrocarbons", "catalog", "CHEBI"));
      assertTrue(reconciled.getNotifications().isEmpty(), reconciled.getDescription());
      assertEquals("CHEBI:24632", reconciled.getId());
    } finally { authority.releaseConfiguration(bridge); }
  }
}
