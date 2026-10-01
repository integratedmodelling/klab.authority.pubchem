package org.integratedmodelling.klab.authority.pubchem;

import java.net.URL;
import java.util.List;
import java.util.Map;
import org.integratedmodelling.klab.api.services.Authority;
import org.integratedmodelling.klab.api.services.runtime.Notification;

/** Immutable chemical identity data, transportable through the shared Authority DTO. */
public record ChemicalIdentity(
    String id, String conceptName, String authorityName, String baseIdentity,
    List<String> parentIds, Map<String, URL> documentation, String description,
    String label, float score, String locator, List<Notification> notifications)
    implements Authority.Identity {
  public ChemicalIdentity {
    parentIds = List.copyOf(parentIds);
    documentation = Map.copyOf(documentation);
    notifications = List.copyOf(notifications);
  }
  @Override public String getId() { return id; }
  @Override public String getConceptName() { return conceptName; }
  @Override public String getAuthorityName() { return authorityName; }
  @Override public String getBaseIdentity() { return baseIdentity; }
  @Override public List<String> getParentIds() { return parentIds; }
  @Override public List<String> getParentRelationship() { return List.of(); }
  @Override public Map<String, URL> getDocumentation() { return documentation; }
  @Override public String getDescription() { return description; }
  @Override public String getLabel() { return label; }
  @Override public float getScore() { return score; }
  @Override public String getLocator() { return locator; }
  @Override public List<Notification> getNotifications() { return notifications; }
}
