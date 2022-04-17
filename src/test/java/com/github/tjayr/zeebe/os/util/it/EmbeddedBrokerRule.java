/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package com.github.tjayr.zeebe.os.util.it;

import io.camunda.zeebe.broker.Broker;
import io.camunda.zeebe.broker.PartitionListener;
import io.camunda.zeebe.broker.SpringBrokerBridge;
import io.camunda.zeebe.broker.system.EmbeddedGatewayService;
import io.camunda.zeebe.broker.system.SystemContext;
import io.camunda.zeebe.broker.system.configuration.BrokerCfg;
import io.camunda.zeebe.broker.system.configuration.ExporterCfg;
import io.camunda.zeebe.broker.system.configuration.NetworkCfg;
import io.camunda.zeebe.engine.state.QueryService;
import io.camunda.zeebe.gateway.impl.broker.BrokerClient;
import io.camunda.zeebe.gateway.impl.broker.cluster.BrokerClusterState;
import io.camunda.zeebe.gateway.impl.broker.cluster.BrokerTopologyManager;
import io.camunda.zeebe.logstreams.log.LogStream;
import io.camunda.zeebe.test.util.TestConfigurationFactory;
import io.camunda.zeebe.test.util.record.RecordingExporter;
import io.camunda.zeebe.test.util.record.RecordingExporterTestWatcher;
import io.camunda.zeebe.test.util.socket.SocketUtil;
import io.camunda.zeebe.util.FileUtil;
import io.camunda.zeebe.util.allocation.DirectBufferAllocator;
import io.camunda.zeebe.util.sched.clock.ControlledActorClock;
import io.camunda.zeebe.util.sched.future.ActorFuture;
import io.camunda.zeebe.util.sched.future.CompletableActorFuture;
import org.assertj.core.util.Files;
import org.junit.rules.ExternalResource;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static io.camunda.zeebe.test.util.TestUtil.waitUntil;
import static org.assertj.core.api.Assertions.assertThat;

public class EmbeddedBrokerRule extends ExternalResource {

  public static final String DEFAULT_CONFIG_FILE = "zeebe.test.cfg.yaml";
  public static final int DEFAULT_TIMEOUT = 25;
  public static final String TEST_RECORD_EXPORTER_ID = "test-recorder";
  protected static final Logger LOG = LoggerFactory.getLogger("io.camunda.zeebe.test");
  protected long startTime;
  private final Consumer<BrokerCfg>[] configurators;
  private final RecordingExporterTestWatcher recordingExporterTestWatcher =
      new RecordingExporterTestWatcher();
  private final BrokerCfg brokerCfg;
  private Broker broker;
  private final ControlledActorClock controlledActorClock = new ControlledActorClock();
  private final SpringBrokerBridge springBrokerBridge = new SpringBrokerBridge();
  private final Duration timeout;
  private final File newTemporaryFolder;
  private SystemContext systemContext;

  @SafeVarargs
  public EmbeddedBrokerRule(final Consumer<BrokerCfg>... configurators) {
    this(DEFAULT_CONFIG_FILE, configurators);
  }

  @SafeVarargs
  public EmbeddedBrokerRule(
      final String configFileClasspathLocation, final Consumer<BrokerCfg>... configurators) {
    this(
        () ->
            EmbeddedBrokerRule.class
                .getClassLoader()
                .getResourceAsStream(configFileClasspathLocation),
        DEFAULT_TIMEOUT,
        configurators);
  }

  @SafeVarargs
  public EmbeddedBrokerRule(
      final Supplier<InputStream> configSupplier,
      final int timeout,
      final Consumer<BrokerCfg>... configurators) {
    this(configSupplier, Duration.ofSeconds(timeout), configurators);
  }

  @SafeVarargs
  public EmbeddedBrokerRule(
      final Supplier<InputStream> configSupplier,
      final Duration timeout,
      final Consumer<BrokerCfg>... configurators) {
    this.configurators = configurators;
    this.timeout = timeout;

    newTemporaryFolder = Files.newTemporaryFolder();
    try (final InputStream configStream = configSupplier.get()) {
      if (configStream == null) {
        brokerCfg = new BrokerCfg();
      } else {
        brokerCfg =
            new TestConfigurationFactory()
                .create(null, "zeebe.broker", configStream, BrokerCfg.class);
      }
      configureBroker(brokerCfg);
    } catch (final IOException e) {
      throw new RuntimeException("Unable to open configuration", e);
    }
  }

  public static void assignSocketAddresses(final BrokerCfg brokerCfg) {
    final NetworkCfg network = brokerCfg.getNetwork();
    brokerCfg.getGateway().getNetwork().setPort(SocketUtil.getNextAddress().getPort());
    network.getCommandApi().setPort(SocketUtil.getNextAddress().getPort());
    network.getInternalApi().setPort(SocketUtil.getNextAddress().getPort());
  }

  @Override
  public Statement apply(final Statement base, final Description description) {
    final Statement statement = recordingExporterTestWatcher.apply(base, description);
    return super.apply(statement, description);
  }

  @Override
  public void before() {
    startTime = System.currentTimeMillis();
    startBroker();
    LOG.info("\n====\nBroker startup time: {}\n====\n", (System.currentTimeMillis() - startTime));
    startTime = System.currentTimeMillis();
  }

  @Override
  public void after() {
    try {
      LOG.info("Test execution time: " + (System.currentTimeMillis() - startTime));
      startTime = System.currentTimeMillis();
      stopBroker();
      LOG.info("Broker closing time: " + (System.currentTimeMillis() - startTime));

      final long allocatedMemoryInKb = DirectBufferAllocator.getAllocatedMemoryInKb();
      if (allocatedMemoryInKb > 0) {
        LOG.warn(
            "There are still allocated direct buffers of a total size of {}kB.",
            allocatedMemoryInKb);
      }
    } finally {
      try {
        FileUtil.deleteFolder(newTemporaryFolder.getAbsolutePath());
      } catch (final IOException e) {
        LOG.error("Unexpected error on deleting data.", e);
      }

      controlledActorClock.reset();
    }
  }

  public BrokerCfg getBrokerCfg() {
    return brokerCfg;
  }

  public void stopBroker() {
    if (broker != null) {
      broker.close();
      broker = null;
      try {
        systemContext.getScheduler().stop().get();
      } catch (final InterruptedException | ExecutionException e) {
        throw new RuntimeException(e);
      }
      systemContext = null;
      System.gc();
    }
  }

  public void startBroker() {
    systemContext =
        new SystemContext(brokerCfg, newTemporaryFolder.getAbsolutePath(), controlledActorClock);
    systemContext.getScheduler().start();

    final CountDownLatch latch = new CountDownLatch(brokerCfg.getCluster().getPartitionsCount());

    broker =
        new Broker(
            systemContext,
            springBrokerBridge,
            Collections.singletonList(new LeaderPartitionListener(latch)));

    broker.start().join();

    try {
      final boolean hasLeaderPartition = latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);

      assertThat(hasLeaderPartition)
          .describedAs("Expected the broker to have a leader of the partition within %s", timeout)
          .isTrue();

    } catch (final InterruptedException e) {
      LOG.info("Timeout. Broker was not started within {}", timeout, e);
      Thread.currentThread().interrupt();
    }

    final EmbeddedGatewayService embeddedGatewayService =
        broker.getBrokerContext().getEmbeddedGatewayService();
    if (embeddedGatewayService != null) {
      final BrokerClient brokerClient = embeddedGatewayService.get().getBrokerClient();

      waitUntil(
          () -> {
            final BrokerTopologyManager topologyManager = brokerClient.getTopologyManager();
            final BrokerClusterState topology = topologyManager.getTopology();
            return topology != null && topology.getLeaderForPartition(1) >= 0;
          });
    }
  }

  public void configureBroker(final BrokerCfg brokerCfg) {
    // build-in exporters
    final ExporterCfg exporterCfg = new ExporterCfg();
    exporterCfg.setClassName(RecordingExporter.class.getName());
    brokerCfg.getExporters().put(TEST_RECORD_EXPORTER_ID, exporterCfg);

    // custom configurators
    for (final Consumer<BrokerCfg> configurator : configurators) {
      configurator.accept(brokerCfg);
    }

    // set random port numbers
    assignSocketAddresses(brokerCfg);
  }

  private static class LeaderPartitionListener implements PartitionListener {

    private final CountDownLatch latch;

    LeaderPartitionListener(final CountDownLatch latch) {
      this.latch = latch;
    }

    @Override
    public ActorFuture<Void> onBecomingFollower(final int partitionId, final long term) {
      return CompletableActorFuture.completed(null);
    }

    @Override
    public ActorFuture<Void> onBecomingLeader(
        final int partitionId,
        final long term,
        final LogStream logStream,
        final QueryService queryService) {
      latch.countDown();
      return CompletableActorFuture.completed(null);
    }

    @Override
    public ActorFuture<Void> onBecomingInactive(final int partitionId, final long term) {
      return CompletableActorFuture.completed(null);
    }
  }
}
