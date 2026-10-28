package com.example.leave.service;

import com.example.leave.api.DecisionRequest;
import com.example.leave.api.SubmitLeaveRequest;
import com.example.leave.domain.LeaveBalance;
import com.example.leave.domain.LeaveBalanceRepository;
import com.example.leave.domain.LeaveRequest;
import com.example.leave.domain.LeaveRequestRepository;
import com.example.leave.domain.LeaveStatus;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ProcessInstanceEvent;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Core integration layer: stores leave requests, starts the BPMN process,
 * validates requests (validate-leave worker), completes manager tasks and
 * finalises the outcome (finalize-leave worker).
 */
@Service
public class LeaveService {

    private static final Logger log = LoggerFactory.getLogger(LeaveService.class);

    public static final String PROCESS_ID = "leave-request";
    public static final String ANNUAL = "ANNUAL";
    public static final int DEFAULT_ANNUAL_ENTITLEMENT = 20;

    private final LeaveRequestRepository requests;
    private final LeaveBalanceRepository balances;
    private final CamundaClient camunda;

    public LeaveService(LeaveRequestRepository requests,
                        LeaveBalanceRepository balances,
                        CamundaClient camunda) {
        this.requests = requests;
        this.balances = balances;
        this.camunda = camunda;
    }

    /**
     * Saves the request first (so the worker can read it), then starts process leave-request.
     * Deliberately not @Transactional: the row must be committed before the engine calls the worker.
     */
    public LeaveRequest submit(SubmitLeaveRequest body) {
        LeaveRequest request = new LeaveRequest();
        request.setEmployeeName(body.employeeName().trim());
        request.setEmployeeEmail(normalise(body.employeeEmail()));
        request.setLeaveType(body.leaveType().trim().toUpperCase(Locale.ROOT));
        request.setStartDate(body.startDate());
        request.setEndDate(body.endDate());
        request.setDays(countWorkingDays(body.startDate(), body.endDate()));
        request.setReason(body.reason());
        request.setStatus(LeaveStatus.SUBMITTED);
        request.setStatusMessage("Submitted, validating");
        LeaveRequest saved = requests.save(request);

        balance(saved.getEmployeeEmail());

        Map<String, Object> variables = new HashMap<>();
        variables.put("requestId", saved.getId());
        variables.put("employeeEmail", saved.getEmployeeEmail());
        variables.put("leaveType", saved.getLeaveType());
        variables.put("days", saved.getDays());
        variables.put("startDate", saved.getStartDate().toString());
        variables.put("endDate", saved.getEndDate().toString());

        try {
            ProcessInstanceEvent instance = camunda.newCreateInstanceCommand()
                    .bpmnProcessId(PROCESS_ID)
                    .latestVersion()
                    .variables(variables)
                    .send()
                    .join();
            requests.updateProcessInstanceKey(saved.getId(), instance.getProcessInstanceKey());
            log.info("Started {} instance {} for leave request #{}",
                    PROCESS_ID, instance.getProcessInstanceKey(), saved.getId());
        } catch (RuntimeException e) {
            saved.setStatus(LeaveStatus.REJECTED);
            saved.setStatusMessage("Workflow could not be started: " + e.getMessage());
            requests.save(saved);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Could not start the leave workflow. Is Camunda running?", e);
        }

        return requests.findById(saved.getId()).orElse(saved);
    }

    /**
     * validate-leave: rejects invalid periods and annual leave beyond the remaining balance.
     * Valid requests move to PENDING_APPROVAL.
     */
    @Transactional
    public Map<String, Object> validate(Long requestId) {
        LeaveRequest request = find(requestId);

        boolean valid;
        String message;
        if (request.getEndDate().isBefore(request.getStartDate())) {
            valid = false;
            message = "End date is before start date";
        } else if (request.getDays() <= 0) {
            valid = false;
            message = "The selected period contains no working days";
        } else if (ANNUAL.equals(request.getLeaveType())) {
            int remaining = balance(request.getEmployeeEmail()).getRemainingDays();
            if (request.getDays() > remaining) {
                valid = false;
                message = "Insufficient annual leave balance: requested " + request.getDays()
                        + " day(s), remaining " + remaining;
            } else {
                valid = true;
                message = "OK";
            }
        } else {
            valid = true;
            message = "OK";
        }

        if (valid) {
            request.setStatus(LeaveStatus.PENDING_APPROVAL);
            request.setStatusMessage("Waiting for manager approval");
        } else {
            request.setStatusMessage(message);
        }
        requests.save(request);
        log.info("Validated leave request #{}: valid={}, message={}", requestId, valid, message);

        return Map.of("valid", valid, "validationMessage", message);
    }

    /**
     * Manager decision: finds the open Manager approval user task of this request's
     * process instance and completes it with approved and managerComment.
     */
    public void decide(Long id, DecisionRequest decision) {
        LeaveRequest request = find(id);
        if (request.getStatus() != LeaveStatus.PENDING_APPROVAL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Request " + id + " is not awaiting approval (status " + request.getStatus() + ")");
        }
        Long processInstanceKey = request.getProcessInstanceKey();
        if (processInstanceKey == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Workflow for request " + id + " has not started yet");
        }

        var tasks = camunda.newUserTaskSearchRequest()
                .filter(f -> f.processInstanceKey(processInstanceKey))
                .send()
                .join()
                .items();

        Long userTaskKey = null;
        for (var task : tasks) {
            if ("CREATED".equals(String.valueOf(task.getState()))) {
                userTaskKey = task.getUserTaskKey();
                break;
            }
        }
        if (userTaskKey == null) {
            // The task index can lag slightly behind the engine; the frontend retries on 409.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Approval task for request " + id + " is not available yet, try again shortly");
        }

        String comment = decision.comment() == null ? "" : decision.comment().trim();
        Map<String, Object> variables = new HashMap<>();
        variables.put("approved", decision.approved());
        variables.put("managerComment", comment);

        try {
            camunda.newCompleteUserTaskCommand(userTaskKey)
                    .variables(variables)
                    .send()
                    .join();
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Could not complete the approval task: " + e.getMessage(), e);
        }
        log.info("Manager {} leave request #{} (user task {})",
                decision.approved() ? "approved" : "rejected", id, userTaskKey);
    }

    /**
     * finalize-leave: stores the final outcome; deducts annual leave only after approval.
     * Idempotent, so a retried job cannot deduct the balance twice.
     */
    @Transactional
    public void finalizeRequest(Long id, boolean approved, String managerComment, String validationMessage) {
        LeaveRequest request = find(id);
        if (request.getStatus() == LeaveStatus.APPROVED || request.getStatus() == LeaveStatus.REJECTED) {
            log.info("Leave request #{} already finalised as {}", id, request.getStatus());
            return;
        }

        String comment = managerComment == null ? "" : managerComment.trim();
        if (approved) {
            if (ANNUAL.equals(request.getLeaveType())) {
                LeaveBalance balance = balance(request.getEmployeeEmail());
                balance.setRemainingDays(balance.getRemainingDays() - request.getDays());
                balances.save(balance);
            }
            request.setStatus(LeaveStatus.APPROVED);
            request.setStatusMessage(comment.isEmpty() ? "Approved by manager" : "Approved: " + comment);
        } else {
            boolean rejectedByManager = request.getStatus() == LeaveStatus.PENDING_APPROVAL;
            request.setStatus(LeaveStatus.REJECTED);
            if (rejectedByManager) {
                request.setStatusMessage(comment.isEmpty() ? "Rejected by manager" : "Rejected: " + comment);
            } else {
                request.setStatusMessage(validationMessage);
            }
        }
        requests.save(request);
        notifyEmployee(request);
    }

    /** Returns the employee's balance, creating the default entitlement on first use. */
    @Transactional
    public LeaveBalance balance(String email) {
        String key = normalise(email);
        return balances.findById(key)
                .orElseGet(() -> balances.save(new LeaveBalance(key, DEFAULT_ANNUAL_ENTITLEMENT)));
    }

    /** Monday to Friday days in the inclusive range; 0 if the range is empty or reversed. */
    public static int countWorkingDays(LocalDate start, LocalDate end) {
        if (start == null || end == null || end.isBefore(start)) {
            return 0;
        }
        int count = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            DayOfWeek day = d.getDayOfWeek();
            if (day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY) {
                count++;
            }
        }
        return count;
    }

    public static String normalise(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private LeaveRequest find(Long id) {
        return requests.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Leave request " + id + " not found"));
    }

    /** Notification hook: stand-in for an e-mail. */
    private void notifyEmployee(LeaveRequest request) {
        log.info("Notification to {}: leave request #{} ({} {} to {}) is {}. {}",
                request.getEmployeeEmail(), request.getId(), request.getLeaveType(),
                request.getStartDate(), request.getEndDate(), request.getStatus(), request.getStatusMessage());
    }
}
