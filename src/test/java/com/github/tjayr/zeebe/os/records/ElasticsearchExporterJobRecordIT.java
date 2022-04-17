/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package com.github.tjayr.zeebe.os.records;

import com.github.tjayr.zeebe.os.OpensearchExporter;
import com.github.tjayr.zeebe.os.AbstractOpensearchExporterIntegrationTestCase;
import io.camunda.zeebe.client.api.worker.JobWorker;
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.protocol.record.intent.JobBatchIntent;
import io.camunda.zeebe.protocol.record.intent.JobIntent;
import io.camunda.zeebe.test.util.record.RecordingExporter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class ElasticsearchExporterJobRecordIT
    extends AbstractOpensearchExporterIntegrationTestCase {

  private JobWorker jobWorker;

  @Before
  public void init() {
    elastic.start();

    configuration = getDefaultConfiguration();
    esClient = createOpensearchClient(configuration);

    exporterBrokerRule.configure("es", OpensearchExporter.class, configuration);
    startBroker();
  }

  @After
  public void cleanUp() {
    if (jobWorker != null) {
      jobWorker.close();
    }
  }

  @Test
  public void shouldExportJobRecordWithCustomHeaders() {
    // when
    exporterBrokerRule.deployProcess(
        Bpmn.createExecutableProcess("process")
            .startEvent()
            .serviceTask(
                "task",
                t -> t.zeebeJobType("test").zeebeTaskHeader("x", "1").zeebeTaskHeader("y", "2"))
            .endEvent()
            .done(),
        "process.bpmn");

    final var processInstanceKey = exporterBrokerRule.createProcessInstance("process", Map.of());

    // then
    final var jobCreated =
        RecordingExporter.jobRecords(JobIntent.CREATED)
            .withProcessInstanceKey(processInstanceKey)
            .getFirst();

    assertRecordExported(jobCreated);
  }

  @Test
  public void shouldExportJobRecordWithOverlappingCustomHeaders() {
    // when
    exporterBrokerRule.deployProcess(
        Bpmn.createExecutableProcess("process")
            .startEvent()
            .serviceTask(
                "task",
                t -> t.zeebeJobType("test").zeebeTaskHeader("x", "1").zeebeTaskHeader("x.y", "2"))
            .endEvent()
            .done(),
        "process.bpmn");

    final var processInstanceKey = exporterBrokerRule.createProcessInstance("process", Map.of());

    // then
    final var jobCreated =
        RecordingExporter.jobRecords(JobIntent.CREATED)
            .withProcessInstanceKey(processInstanceKey)
            .getFirst();

    assertRecordExported(jobCreated);
  }

  @Test
  public void shouldExportJobBatchRecordWithOverlappingCustomHeaders() {
    // when
    exporterBrokerRule.deployProcess(
        Bpmn.createExecutableProcess("process")
            .startEvent()
            .serviceTask(
                "task",
                t -> t.zeebeJobType("test").zeebeTaskHeader("x", "1").zeebeTaskHeader("x.y", "2"))
            .endEvent()
            .done(),
        "process.bpmn");

    final var processInstanceKey = exporterBrokerRule.createProcessInstance("process", Map.of());

    final var jobCreated =
        RecordingExporter.jobRecords(JobIntent.CREATED)
            .withProcessInstanceKey(processInstanceKey)
            .getFirst();

    jobWorker =
        exporterBrokerRule.createJobWorker(
            "test", ((client, job) -> client.newCompleteCommand(job.getKey()).send()));

    // then
    final var jobBatchActivated =
        RecordingExporter.jobBatchRecords(JobBatchIntent.ACTIVATED).withType("test").getFirst();

    assertThat(jobBatchActivated.getValue().getJobKeys()).contains(jobCreated.getKey());
    assertRecordExported(jobBatchActivated);
  }
}
