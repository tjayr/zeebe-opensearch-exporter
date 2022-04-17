/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package com.github.tjayr.zeebe.os;

import io.camunda.zeebe.exporter.api.ExporterException;
import io.camunda.zeebe.protocol.record.Record;
import io.camunda.zeebe.test.util.TestUtil;
import io.camunda.zeebe.test.util.record.RecordingExporter;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class OpensearchExporterConfigurationIT extends AbstractOpensearchExporterIntegrationTestCase {

  @Test
  public void shouldPropagateNumberOfShardsAndReplicas() {
    // given
    elastic.start();

    configuration = getDefaultConfiguration();

    // change number of shards and replicas
    configuration.index.setNumberOfShards(5);
    configuration.index.setNumberOfReplicas(4);

    esClient = createOpensearchClient(configuration);

    // when
    exporterBrokerRule.configure("es", OpensearchExporter.class, configuration);
    startBroker();
    exporterBrokerRule.publishMessage("message", "123");

    // then
    RecordingExporter.messageRecords()
        .withCorrelationKey("123")
        .withName("message")
        .forEach(r -> TestUtil.waitUntil(() -> wasExported(r)));
    assertIndexSettings();
  }

  @Test
  public void shouldFailWhenNumberOfShardsIsLessOne() {
    // given
    elastic.start();

    configuration = getDefaultConfiguration();

    // change number of shards
    configuration.index.setNumberOfShards(-1);

    esClient = createOpensearchClient(configuration);

    // when
    exporterBrokerRule.configure("es", OpensearchExporter.class, configuration);

    // then
    assertThatThrownBy(this::startBroker)
        .isInstanceOf(IllegalStateException.class)
        .getRootCause()
        .isInstanceOf(ExporterException.class)
        .hasMessageContaining("numberOfShards must be >= 1. Current value: -1");
  }

  @Test
  public void shouldFailWhenNumberOfReplicasIsLessZero() {
    // given
    elastic.start();

    configuration = getDefaultConfiguration();

    // change number replicas
    configuration.index.setNumberOfReplicas(-1);

    esClient = createOpensearchClient(configuration);

    // when
    exporterBrokerRule.configure("es", OpensearchExporter.class, configuration);

    // then
    assertThatThrownBy(this::startBroker)
        .isInstanceOf(IllegalStateException.class)
        .getRootCause()
        .isInstanceOf(ExporterException.class)
        .hasMessageContaining("numberOfReplicas must be >= 0. Current value: -1");
  }

  private boolean wasExported(final Record<?> record) {
    try {
      return esClient.getDocument(record) != null;
    } catch (final Exception e) {
      // suppress exception in order to retry and see if it was exported yet or not
      // the exception can occur since elastic may not be ready yet, or maybe the index hasn't been
      // created yet, etc.
    }

    return false;
  }
}
