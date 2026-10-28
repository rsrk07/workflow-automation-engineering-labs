package com.example.leave.api;

import com.example.leave.domain.LeaveRequest;
import com.example.leave.domain.LeaveRequestRepository;
import com.example.leave.domain.LeaveStatus;
import com.example.leave.service.LeaveService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/leaves")
public class LeaveController {

    private final LeaveService service;
    private final LeaveRequestRepository requests;

    public LeaveController(LeaveService service, LeaveRequestRepository requests) {
        this.service = service;
        this.requests = requests;
    }

    /** Submit a leave request; starts the leave-request process. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LeaveRequest submit(@Valid @RequestBody SubmitLeaveRequest body) {
        return service.submit(body);
    }

    /** An employee's own requests, newest first. */
    @GetMapping
    public List<LeaveRequest> mine(@RequestParam String employeeEmail) {
        return requests.findByEmployeeEmailOrderByCreatedAtDesc(LeaveService.normalise(employeeEmail));
    }

    /** Requests waiting at the Manager approval user task. */
    @GetMapping("/pending")
    public List<LeaveRequest> pending() {
        return requests.findByStatusOrderByCreatedAtAsc(LeaveStatus.PENDING_APPROVAL);
    }

    /** Manager decision: completes the Camunda user task. */
    @PostMapping("/{id}/decision")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decide(@PathVariable Long id, @RequestBody DecisionRequest body) {
        service.decide(id, body);
    }

    /** Remaining annual leave for an employee. */
    @GetMapping("/balance")
    public Map<String, Integer> balance(@RequestParam String employeeEmail) {
        return Map.of("remainingDays", service.balance(employeeEmail).getRemainingDays());
    }
}
