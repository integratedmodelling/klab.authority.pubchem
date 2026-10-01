package org.integratedmodelling.klab.authority.pubchem;

import org.integratedmodelling.klab.extension.KlabComponent;
import org.pf4j.PluginWrapper;

/** PF4J entry point required by the k.LAB component manager. */
public class ComponentPlugin extends KlabComponent {

  public ComponentPlugin(PluginWrapper wrapper) {
    super(wrapper);
  }
}
