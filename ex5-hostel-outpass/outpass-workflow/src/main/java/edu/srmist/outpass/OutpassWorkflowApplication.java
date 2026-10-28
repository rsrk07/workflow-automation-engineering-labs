package edu.srmist.outpass;

import io.camunda.client.annotation.Deployment;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Hostel Out-Pass Request workflow.
 * On start-up the BPMN model and both forms are deployed to the Camunda 8 SaaS cluster.
 */
@SpringBootApplication
@Deployment(resources = {
        "classpath:bpmn/hostel-outpass.bpmn",
        "classpath:bpmn/warden-approval.form",
        "classpath:bpmn/chief-warden-approval.form"
})
public class OutpassWorkflowApplication {

    public static void main(String[] args) {
        SpringApplication.run(OutpassWorkflowApplication.class, args);
    }
}
