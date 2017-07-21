package org.mule.runtime.module.deployment.impl.internal.artifact;

import static org.mule.runtime.core.api.config.MuleProperties.OBJECT_CONNECTION_MANAGER;
import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.core.api.config.ConfigurationBuilder;
import org.mule.runtime.core.api.config.ConfigurationException;
import org.mule.runtime.core.api.registry.RegistrationException;
import org.mule.runtime.core.internal.connection.CompositeConnectionManager;
import org.mule.runtime.core.internal.connection.DefaultConnectionManager;
import org.mule.runtime.deployment.model.api.DeployableArtifact;

/**
 *
 *
 * @since 4.0
 */
public class ConnectionManagerConfigurationBuilder implements ConfigurationBuilder {

    private DeployableArtifact parentArtifact;
    private boolean isConfigured = false;

    ConnectionManagerConfigurationBuilder(DeployableArtifact parentArtifact) {
        this.parentArtifact = parentArtifact;
    }

    @Override
    public void configure(MuleContext muleContext) throws ConfigurationException {
        try {
            if (parentArtifact == null) {
                muleContext.getRegistry().registerObject(OBJECT_CONNECTION_MANAGER, new DefaultConnectionManager(muleContext));
            } else {
                muleContext.getRegistry().registerObject(OBJECT_CONNECTION_MANAGER,
                        new CompositeConnectionManager(new DefaultConnectionManager(muleContext),
                                parentArtifact.getMuleContext()
                                        .getRegistry()
                                        .get(OBJECT_CONNECTION_MANAGER)));
            }
            isConfigured = true;
        } catch (RegistrationException e) {
            throw new ConfigurationException(e);
        }
    }

    @Override
    public boolean isConfigured() {
        return isConfigured;
    }
}
