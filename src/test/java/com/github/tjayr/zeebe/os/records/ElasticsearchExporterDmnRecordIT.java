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
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.protocol.record.intent.DecisionEvaluationIntent;
import io.camunda.zeebe.protocol.record.intent.DeploymentIntent;
import io.camunda.zeebe.test.util.record.RecordingExporter;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public final class ElasticsearchExporterDmnRecordIT
    extends AbstractOpensearchExporterIntegrationTestCase {

  private static final String DMN_RESOURCE = "dmn/decision-table.dmn";

  @Before
  public void init() {
    elastic.start();

    configuration = getDefaultConfiguration();
    configuration.index.deployment = true;

    esClient = createOpensearchClient(configuration);

    exporterBrokerRule.configure("es", OpensearchExporter.class, configuration);
    startBroker();
  }

  @Test
  public void shouldExportDeploymentRecord() {
    // when
    exporterBrokerRule.deployResourceFromClasspath(DMN_RESOURCE);

    // then
    final var deploymentRecord =
        RecordingExporter.deploymentRecords().withIntent(DeploymentIntent.CREATED).getFirst();

    assertThat(deploymentRecord.getValue().getDecisionRequirementsMetadata()).isNotEmpty();
    assertThat(deploymentRecord.getValue().getDecisionsMetadata()).isNotEmpty();

    assertRecordExported(deploymentRecord);
  }

  @Test
  public void shouldExportDecisionRecord() {
    // when
    exporterBrokerRule.deployResourceFromClasspath(DMN_RESOURCE);

    // then
    final var decisionRecord = RecordingExporter.decisionRecords().getFirst();

    assertRecordExported(decisionRecord);
  }

  @Test
  public void shouldExportDecisionRequirementsRecord() {
    // when
    exporterBrokerRule.deployResourceFromClasspath(DMN_RESOURCE);

    // then
    final var decisionRequirementsRecord =
        RecordingExporter.decisionRequirementsRecords().getFirst();

    assertRecordExported(decisionRequirementsRecord);
  }

  @Test
  public void shouldExportDecisionEvaluationRecord() {
    // given
    exporterBrokerRule.deployResourceFromClasspath(DMN_RESOURCE);

    exporterBrokerRule.deployProcess(
        Bpmn.createExecutableProcess("process")
            .startEvent()
            .businessRuleTask(
                "task", t -> t.zeebeCalledDecisionId("jedi_or_sith").zeebeResultVariable("result"))
            .done(),
        "process.bpmn");

    // when
    final var processInstanceKey =
        exporterBrokerRule.createProcessInstance("process", Map.of("lightsaberColor", "blue"));

    // then
    final var decisionEvaluationRecord =
        RecordingExporter.decisionEvaluationRecords()
            .withIntent(DecisionEvaluationIntent.EVALUATED)
            .withProcessInstanceKey(processInstanceKey)
            .getFirst();

    assertRecordExported(decisionEvaluationRecord);
  }

  @Test
  public void shouldExportDecisionEvaluationRecordWithEvaluationFailure() {
    // given
    exporterBrokerRule.deployResourceFromClasspath(DMN_RESOURCE);

    exporterBrokerRule.deployProcess(
        Bpmn.createExecutableProcess("process")
            .startEvent()
            .businessRuleTask(
                "task", t -> t.zeebeCalledDecisionId("jedi_or_sith").zeebeResultVariable("result"))
            .done(),
        "process.bpmn");

    // when
    final var processInstanceKey = exporterBrokerRule.createProcessInstance("process", Map.of());

    // then
    final var decisionEvaluationRecord =
        RecordingExporter.decisionEvaluationRecords()
            .withIntent(DecisionEvaluationIntent.FAILED)
            .withProcessInstanceKey(processInstanceKey)
            .getFirst();

    assertRecordExported(decisionEvaluationRecord);
  }
}
