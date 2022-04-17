/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package com.github.tjayr.zeebe.os;

import com.github.tjayr.zeebe.os.util.OpensearchNode;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import java.util.function.Consumer;

@RunWith(Parameterized.class)
public class OpensearchExporterAuthenticationIT
    extends AbstractOpensearchExporterIntegrationTestCase {
  @Parameter(0)
  public String name;

  @Parameter(1)
  public Consumer<OpensearchNode> elasticConfigurator;

  @Parameter(2)
  public Consumer<OpensearchExporterConfiguration> exporterConfigurator;

  @Parameters(name = "{0}")
  public static Object[][] data() {
    return new Object[][] {
      new Object[] {
        "defaults", elastic(c -> {}), exporter(c -> {}),
      },
      new Object[] {
        "basic authentication",
        elastic(c -> c.withUser("zeebe", "1234567")),
        exporter(
            c -> {
              c.getAuthentication().setUsername("zeebe");
              c.getAuthentication().setPassword("1234567");
            })
      },
    };
  }

  @Test
  public void shouldExportRecords() {
    // given
    elasticConfigurator.accept(elastic);
    elastic.start();

    // given
    configuration = getDefaultConfiguration();
    exporterConfigurator.accept(configuration);
    exporterBrokerRule.configure("es", OpensearchExporter.class, configuration);
    esClient = createOpensearchClient(configuration);

    // when
    startBroker();
    exporterBrokerRule.performSampleWorkload();

    // then
    // assert all records which where recorded during the tests where exported
    exporterBrokerRule.visitExportedRecords(
        r -> {
          if (configuration.shouldIndexRecord(r)) {
            assertRecordExported(r);
          }
        });
  }

  private static Consumer<OpensearchNode> elastic(
      final Consumer<OpensearchNode> configurator) {
    return configurator;
  }

  private static Consumer<OpensearchExporterConfiguration> exporter(
      final Consumer<OpensearchExporterConfiguration> configurator) {
    return configurator;
  }
}
