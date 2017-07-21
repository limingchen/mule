/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.connection;

import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.disposeIfNeeded;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.initialiseIfNeeded;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.startIfNeeded;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.stopIfNeeded;
import static org.slf4j.LoggerFactory.getLogger;
import org.mule.runtime.api.config.PoolingProfile;
import org.mule.runtime.api.connection.ConnectionException;
import org.mule.runtime.api.connection.ConnectionHandler;
import org.mule.runtime.api.connection.ConnectionProvider;
import org.mule.runtime.api.connection.ConnectionValidationResult;
import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.lifecycle.InitialisationException;
import org.mule.runtime.api.lifecycle.Lifecycle;
import org.mule.runtime.core.api.connector.ConnectionManager;
import org.mule.runtime.core.api.retry.policy.RetryPolicyTemplate;
import org.mule.runtime.extension.api.runtime.ConfigurationInstance;
import org.slf4j.Logger;

public class CompositeConnectionManager implements ConnectionManager, Lifecycle, ConnectionManagerAdapter {

  private static final Logger LOGGER = getLogger(CompositeConnectionManager.class);

  private final ConnectionManagerAdapter childConnectionManager;
  private final ConnectionManagerAdapter parentConnectionManager;

  public CompositeConnectionManager(ConnectionManagerAdapter childConnectionManager,
                                    ConnectionManagerAdapter parentConnectionManager) {
    this.childConnectionManager = childConnectionManager;
    this.parentConnectionManager = parentConnectionManager;
  }

  @Override
  public void dispose() {
    disposeIfNeeded(childConnectionManager, LOGGER);
  }

  @Override
  public void initialise() throws InitialisationException {
    initialiseIfNeeded(childConnectionManager);
  }

  @Override
  public void start() throws MuleException {
    startIfNeeded(childConnectionManager);
  }

  @Override
  public void stop() throws MuleException {
    stopIfNeeded(childConnectionManager);
  }

  @Override
  public <C> void bind(Object config, ConnectionProvider<C> connectionProvider) {
    childConnectionManager.bind(config, connectionProvider);
  }

  @Override
  public boolean hasBinding(Object config) {
    return childConnectionManager.hasBinding(config) || parentConnectionManager.hasBinding(config);
  }

  @Override
  public void unbind(Object config) {
    if (childConnectionManager.hasBinding(config)) {
      childConnectionManager.unbind(config);
    } else if (parentConnectionManager.hasBinding(config)) {
      parentConnectionManager.unbind(config);
    }
  }

  @Override
  public <C> ConnectionHandler<C> getConnection(Object config) throws ConnectionException {
    return childConnectionManager.hasBinding(config) ? childConnectionManager.getConnection(config)
        : parentConnectionManager.getConnection(config);
  }

  @Override
  public <C> ConnectionValidationResult testConnectivity(ConnectionProvider<C> connectionProvider) {
    return childConnectionManager.testConnectivity(connectionProvider);
  }

  @Override
  public ConnectionValidationResult testConnectivity(ConfigurationInstance configurationInstance)
      throws IllegalArgumentException {
    return childConnectionManager.testConnectivity(configurationInstance);
  }

  @Override
  public RetryPolicyTemplate getDefaultRetryPolicyTemplate() {
    return childConnectionManager.getDefaultRetryPolicyTemplate();
  }

  @Override
  public <C> RetryPolicyTemplate getRetryTemplateFor(ConnectionProvider<C> connectionProvider) {
    return childConnectionManager.getDefaultRetryPolicyTemplate();
  }

  @Override
  public PoolingProfile getDefaultPoolingProfile() {
    return childConnectionManager.getDefaultPoolingProfile();
  }
}
