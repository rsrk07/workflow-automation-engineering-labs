package edu.srmist.outpass.api;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ProcessInstanceEvent;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST trigger: one POST /outpass starts one instance of the hostel-outpass process.
 */
@RestController
public class OutpassController {

    private final CamundaClient camundaClient;

    public OutpassController(CamundaClient camundaClient) {
        this.camundaClient = camundaClient;
    }

    @PostMapping("/outpass")
    public Map<String, Object> requestOutpass(@RequestBody OutpassRequest request) {
        ProcessInstanceEvent instance = camundaClient.newCreateInstanceCommand()
                .bpmnProcessId("hostel-outpass")
                .latestVersion()
                .variables(request)
                .send()
                .join();

        return Map.of(
                "processInstanceKey", instance.getProcessInstanceKey(),
                "bpmnProcessId", instance.getBpmnProcessId(),
                "version", instance.getVersion());
    }
}
