/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package com.github.tjayr.zeebe.os;

import io.camunda.zeebe.exporter.api.ExporterException;
import io.camunda.zeebe.exporter.api.context.Configuration;
import io.camunda.zeebe.exporter.api.context.Context;
import io.camunda.zeebe.exporter.api.context.Context.RecordFilter;
import io.camunda.zeebe.protocol.record.RecordType;
import io.camunda.zeebe.protocol.record.ValueType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

final class OpensearchExporterConfigurationTest {

  @Test
  void shouldConfigureValueTypeFilter() {
    // given
    final var exporter = new OpensearchExporter();

    final var enabledValueTypes =
        Stream.of(ValueType.DEPLOYMENT, ValueType.PROCESS, ValueType.ERROR, ValueType.INCIDENT);
    final var disabledValueTypes =
        Stream.of(
            ValueType.VARIABLE_DOCUMENT,
            ValueType.PROCESS_INSTANCE_CREATION,
            ValueType.PROCESS_MESSAGE_SUBSCRIPTION);

    final var config = new OpensearchExporterConfiguration();
    config.index.deployment = true;
    config.index.process = true;
    config.index.error = true;
    config.index.incident = true;
    config.index.variableDocument = false;
    config.index.processInstanceCreation = false;
    config.index.messageSubscription = false;

    final var configuration = mock(Configuration.class);
    when(configuration.instantiate(eq(OpensearchExporterConfiguration.class)))
        .thenReturn(config);

    final var capturedFilter = ArgumentCaptor.forClass(RecordFilter.class);
    final var context = mock(Context.class);
    when(context.getLogger()).thenReturn(mock(Logger.class));
    when(context.getConfiguration()).thenReturn(configuration);

    // when
    exporter.configure(context);
    verify(context).setFilter(capturedFilter.capture());
    final var filter = capturedFilter.getValue();

    // then
    assertThat(enabledValueTypes).allMatch(filter::acceptValue);
    assertThat(disabledValueTypes).noneMatch(filter::acceptValue);
  }

  @Test
  void shouldConfigureRecordTypeFilter() {
    // given
    final var exporter = new OpensearchExporter();
    final var config = new OpensearchExporterConfiguration();

    final var enabledRecordTypes = Stream.of(RecordType.EVENT);
    final var disabledRecordTypes = Stream.of(RecordType.COMMAND);
    config.index.event = true;
    config.index.command = false;

    final var configuration = mock(Configuration.class);
    when(configuration.instantiate(eq(OpensearchExporterConfiguration.class)))
        .thenReturn(config);

    final var capturedFilter = ArgumentCaptor.forClass(RecordFilter.class);
    final var context = mock(Context.class);
    when(context.getLogger()).thenReturn(mock(Logger.class));
    when(context.getConfiguration()).thenReturn(configuration);

    // when
    exporter.configure(context);
    verify(context).setFilter(capturedFilter.capture());
    final var filter = capturedFilter.getValue();

    // then
    assertThat(enabledRecordTypes).allMatch(filter::acceptType);
    assertThat(disabledRecordTypes).noneMatch(filter::acceptType);
  }

  @Test
  void shouldFailOnIllegalPrefix() {
    // given
    final var exporter = new OpensearchExporter();

    final var config = new OpensearchExporterConfiguration();
    config.index.prefix = "prefix_withunderscore";

    final var configuration = mock(Configuration.class);
    when(configuration.instantiate(eq(OpensearchExporterConfiguration.class)))
        .thenReturn(config);

    final var context = mock(Context.class);
    when(context.getLogger()).thenReturn(mock(Logger.class));
    when(context.getConfiguration()).thenReturn(configuration);

    // then
    assertThatThrownBy(() -> exporter.configure(context))
        .withFailMessage(
            "Elasticsearch prefix must not contain underscore. Current value: "
                + config.index.prefix)
        .isInstanceOf(ExporterException.class);
  }
}
