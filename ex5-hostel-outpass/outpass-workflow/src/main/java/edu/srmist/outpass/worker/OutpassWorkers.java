package edu.srmist.outpass.worker;

import io.camunda.client.annotation.JobWorker;
import io.camunda.client.annotation.Variable;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Job workers for the two service tasks in hostel-outpass.bpmn.
 * The job type in @JobWorker must match the task type in the model exactly.
 */
@Component
public class OutpassWorkers {

    private static final Logger log = LoggerFactory.getLogger(OutpassWorkers.class);

    /**
     * Service task "Check request" (type check-outpass).
     * Reads regNo, outDate, returnDate. Writes nights, valid, approved.
     */
    @JobWorker(type = "check-outpass")
    public Map<String, Object> checkOutpass(@Variable String regNo,
                                            @Variable String outDate,
                                            @Variable String returnDate) {
        LocalDate out = LocalDate.parse(outDate);
        LocalDate back = LocalDate.parse(returnDate);

        long nights = ChronoUnit.DAYS.between(out, back);
        boolean valid = !back.isBefore(out);
        // A day out (no night away) is approved automatically; otherwise the warden decides.
        boolean approved = valid && nights == 0;

        log.info("Checked out-pass for {}: {} -> {}, nights={}, valid={}, autoApproved={}",
                regNo, outDate, returnDate, nights, valid, approved);

        return Map.of("nights", nights, "valid", valid, "approved", approved);
    }

    /**
     * Service task "Issue out-pass" (type issue-outpass).
     * Reads regNo, outDate, studentName, parentPhone, approved, wardenRemarks.
     * Writes passStatus and, for approved requests, passNumber.
     */
    @JobWorker(type = "issue-outpass")
    public Map<String, Object> issueOutpass(@Variable String regNo,
                                            @Variable String outDate,
                                            @Variable String studentName,
                                            @Variable String parentPhone,
                                            @Variable Boolean approved,
                                            @Variable String wardenRemarks) {
        boolean isApproved = Boolean.TRUE.equals(approved);
        String passStatus = isApproved ? "ISSUED" : "REJECTED";

        Map<String, Object> result = new HashMap<>();
        result.put("passStatus", passStatus);

        String remarks = (wardenRemarks == null || wardenRemarks.isBlank()) ? "-" : wardenRemarks;
        if (isApproved) {
            String passNumber = "OP-" + regNo + "-" + outDate;
            result.put("passNumber", passNumber);
            log.info("SMS to parent {}: Out-pass {} ISSUED for {} ({}) leaving on {}. Remarks: {}",
                    parentPhone, passNumber, studentName, regNo, outDate, remarks);
        } else {
            log.info("SMS to parent {}: Out-pass request of {} ({}) for {} was REJECTED. Remarks: {}",
                    parentPhone, studentName, regNo, outDate, remarks);
        }
        return result;
    }
}
