/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package com.github.tjayr.zeebe.os;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.zeebe.exporter.dto.BulkItemError;
import io.camunda.zeebe.exporter.dto.BulkResponse;
import io.camunda.zeebe.exporter.dto.PutIndexTemplateResponse;
import io.camunda.zeebe.protocol.record.ValueType;
import io.camunda.zeebe.protocol.record.Record;
import io.camunda.zeebe.util.VersionUtil;
import io.prometheus.client.Histogram;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.opensearch.client.*;
import org.opensearch.common.xcontent.*;


import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public class OpensearchClient {

  @SuppressWarnings("java:S1075") // not an actual URI
  public static final String INDEX_TEMPLATE_FILENAME_PATTERN = "/zeebe-record-%s-template.json";

  public static final String INDEX_DELIMITER = "_";
  public static final String ALIAS_DELIMITER = "-";
  private static final String DOCUMENT_TYPE = "_doc";
  private static final String TEMPLATE_FIELD_KEY = "template";
  private static final ObjectMapper MAPPER = new ObjectMapper();
  protected final RestClient client;
  private final OpensearchExporterConfiguration configuration;
  private final DateTimeFormatter formatter;
  private List<String> bulkRequest;
  private OpensearchMetrics metrics;

  public OpensearchClient(final OpensearchExporterConfiguration configuration) {
    this(configuration, new ArrayList<>());
  }

  OpensearchClient(
          final OpensearchExporterConfiguration configuration, final List<String> bulkRequest) {
    this.configuration = configuration;
    client = createClient();
    this.bulkRequest = bulkRequest;
    formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
  }

  public void close() throws IOException {
    client.close();
  }

  public void index(final Record<?> record) {
    if (metrics == null) {

      metrics = new OpensearchMetrics(record.getPartitionId());
    }

    bulk(newIndexCommand(record), record);
  }

  public void bulk(final Map<String, Object> command, final Record<?> record) {
    final String serializedCommand;

    try {
      serializedCommand = MAPPER.writeValueAsString(command);
    } catch (final IOException e) {
      throw new OpensearchExporterException(
          "Failed to serialize bulk request command to JSON", e);
    }

    final String jsonCommand = serializedCommand + "\n" + record.toJson();
    // don't re-append when retrying same record, to avoid OOM
    if (bulkRequest.isEmpty() || !bulkRequest.get(bulkRequest.size() - 1).equals(jsonCommand)) {
      bulkRequest.add(jsonCommand);
    }
  }

  /**
   * @throws OpensearchExporterException if not all items of the bulk were flushed successfully
   */
  public void flush() {
    if (bulkRequest.isEmpty()) {
      return;
    }

    final int bulkSize = bulkRequest.size();
    metrics.recordBulkSize(bulkSize);

    final var bulkMemorySize = getBulkMemorySize();
    metrics.recordBulkMemorySize(bulkMemorySize);

    try (final Histogram.Timer ignored = metrics.measureFlushDuration()) {
      exportBulk();
      // all records where flushed, create new bulk request, otherwise retry next time
      bulkRequest = new ArrayList<>();
    } catch (final OpensearchExporterException e) {
      metrics.recordFailedFlush();
      throw e;
    }
  }

  private void exportBulk() {
    final Response httpResponse;
    try {
      httpResponse = sendBulkRequest();
    } catch (final ResponseException e) {
      throw new OpensearchExporterException("Elastic returned an error response on flush", e);
    } catch (final IOException e) {
      throw new OpensearchExporterException("Failed to flush bulk", e);
    }

    final BulkResponse bulkResponse;
    try {
      bulkResponse = MAPPER.readValue(httpResponse.getEntity().getContent(), BulkResponse.class);
    } catch (final IOException e) {
      throw new OpensearchExporterException("Failed to parse response when flushing", e);
    }

    if (bulkResponse.hasErrors()) {
      throwCollectedBulkError(bulkResponse);
    }
  }

  private void throwCollectedBulkError(final BulkResponse bulkResponse) {
    final var collectedErrors = new ArrayList<String>();
    bulkResponse.getItems().stream()
        .flatMap(item -> Optional.ofNullable(item.getIndex()).stream())
        .flatMap(index -> Optional.ofNullable(index.getError()).stream())
        .collect(Collectors.groupingBy(BulkItemError::getType))
        .forEach(
            (errorType, errors) ->
                collectedErrors.add(
                    String.format(
                        "Failed to flush %d item(s) of bulk request [type: %s, reason: %s]",
                        errors.size(), errorType, errors.get(0).getReason())));

    throw new OpensearchExporterException("Failed to flush bulk request: " + collectedErrors);
  }

  private Response sendBulkRequest() throws IOException {
    final var request = new Request("POST", "/_bulk");
    request.setJsonEntity(String.join("\n", bulkRequest) + "\n");

    return client.performRequest(request);
  }

  public boolean shouldFlush() {
    return bulkRequest.size() >= configuration.bulk.size
        || getBulkMemorySize() >= configuration.bulk.memoryLimit;
  }

  private int getBulkMemorySize() {
    return bulkRequest.stream().mapToInt(String::length).sum();
  }

  /**
   * @return true if request was acknowledged
   */
  public boolean putIndexTemplate(final ValueType valueType) {
    final String templateName = indexPrefixForValueType(valueType);
    final String aliasName = aliasNameForValueType(valueType);
    final String filename = indexTemplateForValueType(valueType);
    return putIndexTemplate(templateName, aliasName, filename);
  }

  /**
   * @return true if request was acknowledged
   */
  public boolean putIndexTemplate(
      final String templateName, final String aliasName, final String filename) {
    final Map<String, Object> template = getTemplateFromClasspath(filename);

    // update prefix in template in case it was changed in configuration
    template.put("index_patterns", Collections.singletonList(templateName + INDEX_DELIMITER + "*"));
    template.put("composed_of", Collections.singletonList(configuration.index.prefix));

    // update aliases in template in case it was changed in configuration
    final Map<String, Object> templateProperties =
        getOrCreateTemplatePropertyMap(template, templateName);
    templateProperties.put("aliases", Collections.singletonMap(aliasName, Collections.emptyMap()));

    // update number of shards/replicas in template in case it was changed in configuration
    final Map<String, Object> settingsProperties =
        getOrCreateSettingsPropertyMap(templateProperties, templateName);
    updateSettings(settingsProperties);

    return putIndexTemplate(templateName, template);
  }

  private Map<String, Object> getOrCreateTemplatePropertyMap(
      final Map<String, Object> properties, final String templateName) {
    final String field = TEMPLATE_FIELD_KEY;
    return getOrCreateProperties(properties, field, templateName, field);
  }

  private Map<String, Object> getOrCreateSettingsPropertyMap(
      final Map<String, Object> properties, final String templateName) {
    final String field = "settings";
    return getOrCreateProperties(
        properties, field, templateName, String.format("%s.%s", TEMPLATE_FIELD_KEY, field));
  }

  private Map<String, Object> getOrCreateProperties(
      final Map<String, Object> from,
      final String field,
      final String templateName,
      final String context) {
    final Object properties = from.computeIfAbsent(field, key -> new HashMap<String, Object>());
    return convertToMap(properties, context, templateName);
  }

  private void updateSettings(final Map<String, Object> settingsProperties) {
    // update number of shards in template in case it was changed in configuration
    final Integer numberOfShards = configuration.index.getNumberOfShards();
    if (numberOfShards != null) {
      settingsProperties.put("number_of_shards", numberOfShards);
    }

    // update number of replicas in template in case it was changed in configuration
    final Integer numberOfReplicas = configuration.index.getNumberOfReplicas();
    if (numberOfReplicas != null) {
      settingsProperties.put("number_of_replicas", numberOfReplicas);
    }
  }

  private Map<String, Object> convertToMap(
      final Object obj, final String context, final String templateName) {
    if (obj instanceof Map) {
      //noinspection unchecked
      return (Map<String, Object>) obj;
    } else {
      throw new IllegalStateException(
          String.format(
              "Expected the '%s' field of the template '%s' to be a map, but was '%s'",
              context, templateName, obj.getClass()));
    }
  }

  /**
   * @return true if request was acknowledged
   */
  public boolean createComponentTemplate(final String templateName, final String filename) {
    final Map<String, Object> template = getTemplateFromClasspath(filename);

    final Map<String, Object> templateProperties =
        getOrCreateTemplatePropertyMap(template, templateName);
    final Map<String, Object> settingProperties =
        getOrCreateSettingsPropertyMap(templateProperties, templateName);
    updateSettings(settingProperties);

    return putComponentTemplate(templateName, template);
  }

  private Map<String, Object> getTemplateFromClasspath(final String filename) {
    final Map<String, Object> template;
    try (final InputStream inputStream =
        OpensearchExporter.class.getResourceAsStream(filename)) {
      if (inputStream != null) {
        template = convertToMap(XContentType.JSON.xContent(), inputStream);
      } else {
        throw new OpensearchExporterException(
            "Failed to find index template in classpath " + filename);
      }
    } catch (final IOException e) {
      throw new OpensearchExporterException(
          "Failed to load index template from classpath " + filename, e);
    }
    return template;
  }

  /**
   * @return true if request was acknowledged
   */
  private boolean putIndexTemplate(final String templateName, final Object body) {
    try {
      final var request = new Request("PUT", "/_index_template/" + templateName);
      request.setJsonEntity(MAPPER.writeValueAsString(body));

      final var response = client.performRequest(request);
      final var putIndexTemplateResponse =
          MAPPER.readValue(response.getEntity().getContent(), PutIndexTemplateResponse.class);
      return putIndexTemplateResponse.isAcknowledged();
    } catch (final IOException e) {
      throw new OpensearchExporterException("Failed to put index template", e);
    }
  }

  /**
   * @return true if request was acknowledged
   */
  private boolean putComponentTemplate(final String templateName, final Object body) {
    try {
      final var request = new Request("PUT", "/_component_template/" + templateName);
      request.setJsonEntity(MAPPER.writeValueAsString(body));

      final var response = client.performRequest(request);
      final var putIndexTemplateResponse =
          MAPPER.readValue(response.getEntity().getContent(), PutIndexTemplateResponse.class);
      return putIndexTemplateResponse.isAcknowledged();
    } catch (final IOException e) {
      throw new OpensearchExporterException("Failed to put component template", e);
    }
  }

  private RestClient createClient() {
    final HttpHost[] httpHosts = urlsToHttpHosts(configuration.url);
    final RestClientBuilder builder =
        RestClient.builder(httpHosts)
            .setRequestConfigCallback(
                b ->
                    b.setConnectTimeout(configuration.requestTimeoutMs)
                        .setSocketTimeout(configuration.requestTimeoutMs))
            .setHttpClientConfigCallback(this::setHttpClientConfigCallback);

    return builder.build();
  }

  private HttpAsyncClientBuilder setHttpClientConfigCallback(final HttpAsyncClientBuilder builder) {
    // use single thread for rest client
    builder.setDefaultIOReactorConfig(IOReactorConfig.custom().setIoThreadCount(1).build());

    if (configuration.hasAuthenticationPresent()) {
      setupBasicAuthentication(builder);
    }

    return builder;
  }

  private void setupBasicAuthentication(final HttpAsyncClientBuilder builder) {
    final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
    credentialsProvider.setCredentials(
        AuthScope.ANY,
        new UsernamePasswordCredentials(
            configuration.getAuthentication().getUsername(),
            configuration.getAuthentication().getPassword()));

    builder.setDefaultCredentialsProvider(credentialsProvider);
  }

  private static HttpHost[] urlsToHttpHosts(final String urls) {
    return Arrays.stream(urls.split(","))
        .map(String::trim)
        .map(OpensearchClient::urlToHttpHost)
        .toArray(HttpHost[]::new);
  }

  private static HttpHost urlToHttpHost(final String url) {
    final URI uri;
    try {
      uri = new URI(url);
    } catch (final URISyntaxException e) {
      throw new OpensearchExporterException("Failed to parse url " + url, e);
    }

    return new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme());
  }

  protected String indexFor(final Record<?> record) {
    final Instant timestamp = Instant.ofEpochMilli(record.getTimestamp());
    return indexPrefixForValueTypeWithDelimiter(record.getValueType())
        + formatter.format(timestamp);
  }

  protected String idFor(final Record<?> record) {
    return record.getPartitionId() + "-" + record.getPosition();
  }

  protected String typeFor() {
    return DOCUMENT_TYPE;
  }

  protected String indexPrefixForValueTypeWithDelimiter(final ValueType valueType) {
    return indexPrefixForValueType(valueType) + INDEX_DELIMITER;
  }

  private String aliasNameForValueType(final ValueType valueType) {
    return configuration.index.prefix + ALIAS_DELIMITER + valueTypeToString(valueType);
  }

  private String indexPrefixForValueType(final ValueType valueType) {
    final String version = VersionUtil.getVersionLowerCase();
    return configuration.index.prefix
        + INDEX_DELIMITER
        + valueTypeToString(valueType)
        + INDEX_DELIMITER
        + version;
  }

  private static String valueTypeToString(final ValueType valueType) {
    return valueType.name().toLowerCase().replace("_", "-");
  }

  private static String indexTemplateForValueType(final ValueType valueType) {
    return String.format(INDEX_TEMPLATE_FILENAME_PATTERN, valueTypeToString(valueType));
  }

  private Map<String, Object> convertToMap(final XContent content, final InputStream input) {
    try (final XContentParser parser =
        content.createParser(
            NamedXContentRegistry.EMPTY, DeprecationHandler.THROW_UNSUPPORTED_OPERATION, input)) {
      return parser.mapOrdered();
    } catch (final IOException e) {
      throw new OpensearchExporterException("Failed to parse content to map", e);
    }
  }

  private Map<String, Object> newIndexCommand(final Record<?> record) {
    final Map<String, Object> command = new HashMap<>();
    final Map<String, Object> contents = new HashMap<>();
    contents.put("_index", indexFor(record));
    contents.put("_id", idFor(record));
    contents.put("routing", String.valueOf(record.getPartitionId()));

    command.put("index", contents);
    return command;
  }
}
