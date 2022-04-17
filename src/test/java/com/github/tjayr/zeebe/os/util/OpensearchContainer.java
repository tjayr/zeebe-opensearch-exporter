/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package com.github.tjayr.zeebe.os.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpHost;
import org.opensearch.client.Request;
import org.opensearch.client.RestClient;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.utility.Base58;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;

import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED;

public class OpensearchContainer extends GenericContainer<OpensearchContainer>
    implements OpensearchNode<OpensearchContainer> {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int DEFAULT_HTTP_PORT = 9200;
  private static final int DEFAULT_TCP_PORT = 9300;
  private static final String DEFAULT_IMAGE = "opensearchproject/opensearch";

  private boolean isSslEnabled;
  private boolean isAuthEnabled;
  private String username;
  private String password;
  private RestClient client;
  private int port;

  public OpensearchContainer() {
    this(RestClient.class.getPackage().getImplementationVersion());
  }

  public OpensearchContainer(final String version) {
    super(DEFAULT_IMAGE + ":" + version);

    // disable xpack by default to disable all these security warnings
    //withEnv("xpack.security.enabled", "false");
  }

  @Override
  public OpensearchContainer withXpack() {

    return this;//withEnv("xpack.license.self_generated.type", "trial");
  }

  @Override
  public OpensearchContainer withUser(final String username, final String password) {
    this.username = username;
    this.password = password;
    isAuthEnabled = true;


//    return withXpack()
//        .withEnv("xpack.security.enabled", "true")
//        .withEnv("xpack.security.authc.anonymous.username", "anon")
//        .withEnv("xpack.security.authc.anonymous.roles", "superuser")
//        .withEnv("xpack.security.authc.anonymous.authz_exception", "true");
    return this;
  }

  @Override
  public OpensearchContainer withJavaOptions(final String... options) {
    return this;
  }

  @Override
  public OpensearchContainer withKeyStore(final String keyStore) {
    isSslEnabled = true;

    return withXpack()
        .withClasspathResourceMapping(
            keyStore, "/usr/share/elasticsearch/config/keystore.p12", BindMode.READ_WRITE)
        .withEnv("xpack.security.http.ssl.enabled", "true")
        .withEnv("xpack.security.http.ssl.keystore.path", "keystore.p12");
  }

  @Override
  public HttpHost getRestHttpHost() {
    final String scheme = isSslEnabled ? "https" : "http";
    final String host = getContainerIpAddress();
    final int port = this.port > 0 ? this.port : getMappedPort(DEFAULT_HTTP_PORT);

    return new HttpHost(host, port, scheme);
  }

  @Override
  public OpensearchContainer withPort(final int port) {
    this.port = port;
    addFixedExposedPort(port, DEFAULT_HTTP_PORT);
    return this;
  }

  @Override
  protected void doStart() {
    super.doStart();

    if (isAuthEnabled) {
      client = RestClient.builder(getRestHttpHost()).build();
      setupUser();
    }
  }

  @Override
  public void stop() {
    super.stop();

    isAuthEnabled = false;
    isSslEnabled = false;
    username = null;
    password = null;

    if (client != null) {
      try {
        client.close();
      } catch (final IOException e) {
        throw new UncheckedIOException(e);
      }

      client = null;
    }
  }

  @Override
  protected void configure() {
    final var waitStrategy =
        new HttpWaitStrategy()
            .forPort(DEFAULT_HTTP_PORT)
            .forPath("/_cluster/health?wait_for_status=green&timeout=1s")
            .forStatusCodeMatching(status -> status == HTTP_OK || status == HTTP_UNAUTHORIZED);
    waitStrategy.withStartupTimeout(Duration.ofMinutes(2));
    if (isSslEnabled) {
      waitStrategy.usingTls();
    }

//    - cluster.name=opensearch-cluster
//            - node.name=opensearch
//            - discovery.seed_hosts=opensearch
//            - cluster.initial_master_nodes=opensearch
//            - bootstrap.memory_lock=true
//            - plugins.security.ssl.http.enabled=false
//            - "OPENSEARCH_JAVA_OPTS=-Xms512m -Xmx512m" # minimum and maximum Java heap size, recommend setting both to 50% of system RAM

        withEnv("node.name", "opensearch")
        .withEnv("cluster.name", "zeebe")
        .withEnv("bootstrap.memory_lock", "true")
        .withEnv("plugins.security.ssl.http.enabled", "true")
        .withNetworkAliases("opensearch-" + Base58.randomString(6))
        .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms1g -Xmx1g -XX:MaxDirectMemorySize=1073741824");

    addExposedPorts(DEFAULT_HTTP_PORT, DEFAULT_TCP_PORT);
    setWaitStrategy(waitStrategy);
  }

  private void setupUser() {
//    final var request = new Request("POST", "/_xpack/security/user/" + username);
//    final var body =
//        Map.of(
//            "roles", Collections.singleton("zeebe-exporter"), "password", password.toCharArray());
//
//    try {
//      request.setJsonEntity(MAPPER.writeValueAsString(body));
//      createRole(client);
//      client.performRequest(request);
//    } catch (final IOException e) {
//      throw new RuntimeException(e);
//    }
  }

  // note: caveat, do not use custom index prefixes!
  private void createRole(final RestClient client) throws IOException {
//    final var request = new Request("PUT", "/_xpack/security/role/zeebe-exporter");
//    request.setJsonEntity("{}");
//    client.performRequest(request);
  }
}
