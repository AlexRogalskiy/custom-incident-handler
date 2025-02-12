package com.camunda.example.incident;

import com.camunda.example.dto.ErrorForIncident;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.impl.context.Context;
import org.camunda.bpm.engine.impl.incident.DefaultIncidentHandler;
import org.camunda.bpm.engine.impl.incident.IncidentContext;
import org.camunda.bpm.engine.impl.incident.IncidentHandler;
import org.camunda.bpm.engine.runtime.Incident;
import org.camunda.bpm.engine.variable.Variables;
import org.camunda.bpm.engine.variable.value.ObjectValue;
import org.camunda.bpm.model.bpmn.instance.Activity;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaProperties;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
public class MyFailedJobIncidentHandler extends DefaultIncidentHandler implements IncidentHandler {

  public MyFailedJobIncidentHandler(String type) {
    super(type);
  }

  @Getter
  private List<String> activityIds = new ArrayList<>();

  @Override
  public Incident handleIncident(IncidentContext context, String message) {
    RuntimeService runtimeService = Context.getProcessEngineConfiguration().getRuntimeService();
    RepositoryService repositoryService = Context.getProcessEngineConfiguration().getRepositoryService();

    activityIds.add(context.getActivityId());
    log.info("MY_FAILED_JOB_INCIDENT: " + message);

    var modelinstance = repositoryService.getBpmnModelInstance(context.getProcessDefinitionId());
    Activity activity = modelinstance.getModelElementById(context.getFailedActivityId());
    var camProps = activity.getExtensionElements().getElementsQuery()
        .filterByType(CamundaProperties.class).singleResult();
    boolean signalIncident = false;
    String signalName = null;

    // read extension properties
    if (camProps != null) {
      for (CamundaProperty prop : camProps.getCamundaProperties()) {
        log.debug("Camunda property {} with value {}", prop.getCamundaName(), prop.getCamundaValue());
        if (prop.getCamundaName().equals("signalIncident") && Boolean.parseBoolean(prop.getCamundaValue()))
          signalIncident = true;
        if (prop.getCamundaName().equals("signalName"))
          signalName = prop.getCamundaValue();
      }
    }
    // if signalIncident extension property is set to true and signal name is set, send signal
    if (signalIncident && null != signalName) {
      log.info("Sending signal {} ...", signalName);
      var executionId = context.getExecutionId();
      String processInstanceId = runtimeService.createExecutionQuery()
          .executionId(executionId).singleResult().getProcessInstanceId();

      //create DTO
      ErrorForIncident errorForIncident = new ErrorForIncident();
      errorForIncident.setActivityId(context.getFailedActivityId());
      errorForIncident.setProcessInstance(processInstanceId);
      errorForIncident.setErrorMessage(message);
      errorForIncident.setJobId(context.getJobDefinitionId());
      //serialize to JSON process data
      ObjectValue errorObj = Variables.objectValue(errorForIncident)
          .serializationDataFormat(Variables.SerializationDataFormats.JSON)
          .create();
      log.info("errorForIncident object created: {}", errorObj);

      //send Signal with error info as payload
      runtimeService.createSignalEvent(signalName)
          .setVariables(Map.of("errorForIncident", errorObj))
          .send();
    }

    return super.handleIncident(context, message);
  }
}
