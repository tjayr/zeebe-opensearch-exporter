/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package com.github.tjayr.zeebe.os;

import io.camunda.zeebe.exporter.api.context.Configuration;
import io.camunda.zeebe.exporter.api.context.Context;
import io.camunda.zeebe.exporter.api.context.Controller;
import io.camunda.zeebe.protocol.record.Record;
import io.camunda.zeebe.protocol.record.ValueType;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import static com.github.tjayr.zeebe.os.OpensearchExporter.ZEEBE_RECORD_TEMPLATE_JSON;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

final class OpensearchExporterTest {

  @Test
  void shouldNotFailOnOpenIfElasticIsUnreachable() {
    // given
    final var config = new OpensearchExporterConfiguration();
    final var configuration = mock(Configuration.class);
    final var context = mock(Context.class);
    when(configuration.instantiate(eq(OpensearchExporterConfiguration.class)))
        .thenReturn(config);
    when(context.getConfiguration()).thenReturn(configuration);
    when(context.getLogger()).thenReturn(mock(Logger.class));

    final var controller = mock(Controller.class);
    final var client = new OpensearchClient(config);
    final var exporter =
        new OpensearchExporter() {
          @Override
          protected OpensearchClient createClient() {
            return client;
          }
        };

    // when
    exporter.configure(context);

    // then -- open is successful
    exporter.open(controller);
    // and -- export fails
    assertThatThrownBy(() -> exporter.export(mock(Record.class)))
        .isInstanceOf(OpensearchExporterException.class);
  }

  @Test
  void shouldCreateIndexTemplates() {
    // given
    final var config = new OpensearchExporterConfiguration();
    final var configuration = mock(Configuration.class);
    final var context = mock(Context.class);
    when(configuration.instantiate(eq(OpensearchExporterConfiguration.class)))
        .thenReturn(config);
    when(context.getConfiguration()).thenReturn(configuration);
    when(context.getLogger()).thenReturn(mock(Logger.class));

    final var controller = mock(Controller.class);
    final var client = mock(OpensearchClient.class);
    final var exporter =
        new OpensearchExporter() {
          @Override
          protected OpensearchClient createClient() {
            return client;
          }
        };
    config.index.prefix = "foo-bar";
    config.index.createTemplate = true;
    config.index.deployment = true;
    config.index.process = true;
    config.index.error = true;
    config.index.incident = true;
    config.index.job = true;
    config.index.jobBatch = true;
    config.index.message = true;
    config.index.messageSubscription = true;
    config.index.variable = true;
    config.index.variableDocument = true;
    config.index.processInstance = true;
    config.index.processInstanceCreation = true;
    config.index.processMessageSubscription = true;

    // when
    exporter.configure(context);
    exporter.open(controller);
    exporter.export(mock(Record.class));

    // then
    verify(client).createComponentTemplate("foo-bar", ZEEBE_RECORD_TEMPLATE_JSON);

    verify(client).putIndexTemplate(ValueType.DEPLOYMENT);
    verify(client).putIndexTemplate(ValueType.PROCESS);
    verify(client).putIndexTemplate(ValueType.ERROR);
    verify(client).putIndexTemplate(ValueType.INCIDENT);
    verify(client).putIndexTemplate(ValueType.JOB);
    verify(client).putIndexTemplate(ValueType.JOB_BATCH);
    verify(client).putIndexTemplate(ValueType.MESSAGE);
    verify(client).putIndexTemplate(ValueType.MESSAGE_SUBSCRIPTION);
    verify(client).putIndexTemplate(ValueType.VARIABLE);
    verify(client).putIndexTemplate(ValueType.VARIABLE_DOCUMENT);
    verify(client).putIndexTemplate(ValueType.PROCESS_INSTANCE);
    verify(client).putIndexTemplate(ValueType.PROCESS_INSTANCE_CREATION);
    verify(client).putIndexTemplate(ValueType.PROCESS_MESSAGE_SUBSCRIPTION);
  }
}
