/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package com.github.tjayr.zeebe.os;

import io.camunda.zeebe.protocol.record.Record;
import io.camunda.zeebe.test.util.record.RecordingExporter;
import io.camunda.zeebe.test.util.socket.SocketUtil;
import org.awaitility.Awaitility;
import org.junit.Ignore;
import org.junit.Test;

import java.time.Duration;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

public class OpensearchExporterFaultToleranceIT
    extends AbstractOpensearchExporterIntegrationTestCase {

  @Test
  @Ignore
  public void shouldExportEvenIfElasticNotInitiallyReachable() {
    // given
    elastic.withPort(SocketUtil.getNextAddress().getPort());
    configuration = getDefaultConfiguration();
    configuration.index.prefix = "zeebe";
    esClient = createOpensearchClient(configuration);

    // when
    exporterBrokerRule.configure("es", OpensearchExporter.class, configuration);
    startBrokerWithoutWaitingForIndexTemplates();
    exporterBrokerRule.publishMessage("message", "123");
    elastic.start();
    //awaitIndexTemplatesCreation();

    // then
    final var records =
        RecordingExporter.messageRecords()
            .withCorrelationKey("123")
            .withName("message")
            .collect(Collectors.toList()); // collect here because we iterate multiple times
    Awaitility.await()
        .timeout(Duration.ofMinutes(1))
        .untilAsserted(() -> assertThat(records).allMatch(this::wasExported));
    assertIndexSettings();
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
