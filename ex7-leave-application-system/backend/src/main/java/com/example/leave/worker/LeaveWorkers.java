package com.example.leave.worker;

import com.example.leave.service.LeaveService;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.annotation.Variable;
import io.camunda.client.api.response.ActivatedJob;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class LeaveWorkers {

    private final LeaveService service;

    public LeaveWorkers(LeaveService service) {
        this.service = service;
    }

    /** Service task "Validate leave request". Returns valid + validationMessage. */
    @JobWorker(type = "validate-leave")
    public Map<String, Object> validate(@Variable Long requestId) {
        return service.validate(requestId);
    }

    /**
     * Service tasks "Finalize as approved" / "Finalize as rejected".
     * Reads the full variable map because managerComment does not exist on the invalid-request path.
     */
    @JobWorker(type = "finalize-leave")
    public void finalizeLeave(ActivatedJob job) {
        Map<String, Object> variables = job.getVariablesAsMap();
        Long id = ((Number) variables.get("requestId")).longValue();
        boolean approved = "APPROVED".equals(variables.get("outcome"));
        Object comment = variables.get("managerComment");
        Object validationMessage = variables.get("validationMessage");
        service.finalizeRequest(
                id,
                approved,
                comment == null ? "" : comment.toString(),
                validationMessage == null ? "OK" : validationMessage.toString());
    }
}
