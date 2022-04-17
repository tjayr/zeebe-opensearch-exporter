/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package com.github.tjayr.zeebe.os;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tjayr.zeebe.os.dto.GetDocumentResponse;
import com.github.tjayr.zeebe.os.dto.GetSettingsForIndicesResponse;
import com.github.tjayr.zeebe.os.util.OpensearchContainer;
import com.github.tjayr.zeebe.os.util.OpensearchNode;
import com.github.tjayr.zeebe.os.util.it.ExporterIntegrationRule;
import com.github.tjayr.zeebe.os.util.it.NonStartableBrokerRule;
import io.camunda.zeebe.protocol.record.Record;
import io.camunda.zeebe.protocol.record.ValueType;
//import io.camunda.zeebe.protocol.record.ValueTypeMapping;
import io.camunda.zeebe.test.util.record.RecordingExporterTestWatcher;
import org.assertj.core.api.Assertions;
import org.awaitility.Awaitility;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.opensearch.client.Request;
import org.opensearch.client.ResponseException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

public abstract class AbstractOpensearchExporterIntegrationTestCase {
  private static final ObjectMapper MAPPER = new ObjectMapper();

//  static GenericContainer<?> opensearchContainer = new GenericContainer<>(
//          DockerImageName.parse("opensearchproject/opensearch")
//                  // Aiven managed version
//                  .withTag("1.1.0")
//  );

  @Rule
  public final RecordingExporterTestWatcher recordingExporterTestWatcher =
      new RecordingExporterTestWatcher();

  protected OpensearchNode<OpensearchContainer> elastic;
  protected OpensearchExporterConfiguration configuration;
  protected OpensearchTestClient esClient;

  private final ExporterIntegrationRule exporterIntegrationRule = new ExporterIntegrationRule();
  protected final NonStartableBrokerRule exporterBrokerRule = exporterIntegrationRule;

  @Before
  public void setUp() {

    elastic = new OpensearchContainer("1.3.1")
            .withEnv("ES_JAVA_OPTS", "-Xms750m -Xmx750m")
            .withEnv("discovery.type", "single-node")
            .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
            .withEnv("DISABLE_SECURITY_PLUGIN", "true")
            .withExposedPorts(9200);

//    opensearchContainer.withExposedPorts(9200);
//    opensearchContainer.setWaitStrategy((new HttpWaitStrategy())
//            .forPort(9200)
//            .forStatusCodeMatching(response -> response == 200 || response == 401));
//    elastic.withEnv("discovery.type", "single-node");
//    elastic.withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true");
//    elastic.withEnv("DISABLE_SECURITY_PLUGIN", "true");
    elastic.start();
  }

  @After
  public void tearDown() throws IOException {
    if (esClient != null) {
      esClient.close();
      esClient = null;
    }


    exporterIntegrationRule.stop();
    elastic.stop();
    configuration = null;
  }

  /**
   * Starts the broker AND waits for all index templates to be present in Elastic before continuing.
   */
  protected void startBroker() {
    startBrokerWithoutWaitingForIndexTemplates();
    //awaitIndexTemplatesCreation();
  }

  protected void startBrokerWithoutWaitingForIndexTemplates() {
    exporterIntegrationRule.start();
  }

//  protected void awaitIndexTemplatesCreation() {
//    final var expectedIndexTemplatesCount =
//        ValueTypeMapping.getAcceptedValueTypes().stream()
//            .filter(configuration::shouldIndexValueType)
//            .count();
//
//    // force exporting to ensure that we create the index templates, then wait for them to have been
//    // created
//    exporterBrokerRule.publishMessage("dummy", "");
//    Awaitility.await("until all indices have been created")
//        .atMost(Duration.ofMinutes(2))
//        .pollInterval(Duration.ofSeconds(1))
//        .until(
//            () -> esClient.getIndexTemplateCount(configuration.index.prefix),
//            size -> size >= expectedIndexTemplatesCount);
//  }

  protected void assertIndexSettings() {
    final var settingsForIndices = esClient.getSettingsForIndices();
    final var indices = settingsForIndices.getIndices();
    Assertions.assertThat(indices).isNotEmpty();

    for (final String indexName : indices.keySet()) {
      final GetSettingsForIndicesResponse.IndexSettings settings = indices.get(indexName);
      final Integer numberOfShards = settings.getNumberOfShards();
      final Integer numberOfReplicas = settings.getNumberOfReplicas();

      final int expectedNumberOfShards = numberOfShardsForIndex(indexName);
      final int expectedNumberOfReplicas = numberOfReplicasForIndex();

      assertThat(numberOfShards)
          .withFailMessage(
              "Expected number of shards of index %s to be %d but was %d",
              indexName, expectedNumberOfShards, numberOfShards)
          .isEqualTo(expectedNumberOfShards);
      assertThat(numberOfReplicas)
          .withFailMessage(
              "Expected number of replicas of index %s to be %d but was %d",
              indexName, expectedNumberOfReplicas, numberOfReplicas)
          .isEqualTo(expectedNumberOfReplicas);
    }
  }

  protected void assertRecordExported(final Record<?> record) {
    await("index templates need to be created")
        .atMost(Duration.ofMinutes(1))
        .untilAsserted(this::assertIndexSettings);

    Awaitility.await("Expected the record to be exported: " + record.toJson())
        .ignoreExceptionsInstanceOf(OpensearchExporterException.class)
        .untilAsserted(
            () -> {
              final Map<String, Object> source = esClient.getDocument(record);

              assertThat(source)
                  .withFailMessage("Failed to fetch record %s from elasticsearch", record)
                  .isNotNull()
                  .isEqualTo(recordToMap(record));
            });
  }

  protected OpensearchTestClient createOpensearchClient(final OpensearchExporterConfiguration configuration) {
    return new  OpensearchTestClient(configuration);
  }

  protected Map<String, Object> recordToMap(final Record<?> record) {
    final JsonNode jsonNode;
    try {
      jsonNode = MAPPER.readTree(record.toJson());
    } catch (final IOException e) {
      throw new AssertionError("Failed to deserialize json of record " + record.toJson(), e);
    }

    return MAPPER.convertValue(jsonNode, new TypeReference<>() {});
  }

  private int numberOfReplicasForIndex() {
    if (configuration != null && configuration.index.getNumberOfReplicas() != null) {
      return configuration.index.getNumberOfReplicas();
    } else {
      return 0;
    }
  }

  private int numberOfShardsForIndex(final String indexName) {
    if (configuration != null && configuration.index.getNumberOfShards() != null) {
      return configuration.index.getNumberOfShards();
    } else if (indexName.startsWith(
            esClient.indexPrefixForValueTypeWithDelimiter(ValueType.PROCESS_INSTANCE))
        || indexName.startsWith(esClient.indexPrefixForValueTypeWithDelimiter(ValueType.JOB))) {
      return 3;
    } else {
      return 1;
    }
  }

  protected OpensearchExporterConfiguration getDefaultConfiguration() {
    final OpensearchExporterConfiguration configuration =
        new OpensearchExporterConfiguration();

    configuration.url = elastic.getRestHttpHost().toString();

    configuration.bulk.delay = 1;
    configuration.bulk.size = 1;

    configuration.index.prefix = "test-record";
    configuration.index.createTemplate = true;
    configuration.index.command = true;
    configuration.index.event = true;
    configuration.index.rejection = true;
    configuration.index.deployment = false;
    configuration.index.process = true;
    configuration.index.error = true;
    configuration.index.incident = true;
    configuration.index.job = true;
    configuration.index.jobBatch = true;
    configuration.index.message = true;
    configuration.index.messageSubscription = true;
    configuration.index.variable = true;
    configuration.index.variableDocument = true;
    configuration.index.processInstance = true;
    configuration.index.processInstanceCreation = true;
    configuration.index.processMessageSubscription = true;

    return configuration;
  }

  public static class OpensearchTestClient extends OpensearchClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    OpensearchTestClient(final OpensearchExporterConfiguration configuration) {
      super(configuration);
    }

    public GetSettingsForIndicesResponse getSettingsForIndices() {
      final var request = new Request("GET", "/_all/_settings");
      try {
        final var response = client.performRequest(request);
        final var statusLine = response.getStatusLine();
        if (statusLine.getStatusCode() >= 400) {
          throw new OpensearchExporterException(
              "Failed to get index settings: " + statusLine.getReasonPhrase());
        }

        return MAPPER.readValue(
            response.getEntity().getContent(), GetSettingsForIndicesResponse.class);
      } catch (final IOException e) {
        throw new OpensearchExporterException("Failed to get index settings", e);
      }
    }

    public int getIndexTemplateCount(final String indexPrefix) {
      final var request = new Request("GET", "/_index_template/" + indexPrefix + "*");
      try {
        final var response = client.performRequest(request);
        final Map<String, List<Object>> indexTemplates =
            MAPPER.readValue(response.getEntity().getContent(), new TypeReference<>() {});
        return indexTemplates.getOrDefault("index_templates", new ArrayList<>()).size();
      } catch (final ResponseException e) {
        // you might get a 404 if there are no templates matching the pattern yet, but the response
        // will be valid and of the same format
        if (e.getResponse().getStatusLine().getStatusCode() == 404) {
          return 0;
        }

        throw new OpensearchExporterException("Failed to get index template count", e);
      } catch (final Exception e) {
        throw new OpensearchExporterException("Failed to get index template count", e);
      }
    }

    public Map<String, Object> getDocument(final Record<?> record) {
      final var request =
          new Request("GET", "/" + indexFor(record) + "/" + typeFor() + "/" + idFor(record));
      request.addParameter("routing", String.valueOf(record.getPartitionId()));
      try {
        final var response = client.performRequest(request);
        final var document =
            MAPPER.readValue(response.getEntity().getContent(), GetDocumentResponse.class);
        return document.getSource();
      } catch (final IOException e) {
        throw new OpensearchExporterException(
            "Failed to get record " + idFor(record) + " from index " + indexFor(record), e);
      }
    }
  }
}
