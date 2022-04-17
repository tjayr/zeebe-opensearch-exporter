/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package com.github.tjayr.zeebe.os.util.it;

import io.camunda.zeebe.client.ZeebeClient;
import org.junit.rules.ExternalResource;

import java.time.Duration;
import java.util.Properties;
import java.util.function.Supplier;

public class ClientRule extends ExternalResource {

  private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(2);
  protected final Supplier<Properties> properties;
  protected ZeebeClient client;

  public ClientRule(final Supplier<Properties> propertiesProvider) {
    properties = propertiesProvider;
  }

  public ZeebeClient getClient() {
    return client;
  }

  @Override
  protected void before() {
    createClient();
  }

  @Override
  protected void after() {
    destroyClient();
  }

  public void createClient() {
    client =
        ZeebeClient.newClientBuilder()
            .defaultRequestTimeout(DEFAULT_REQUEST_TIMEOUT)
            .withProperties(properties.get())
            .build();
  }

  public void destroyClient() {
    client.close();
    client = null;
  }
}
