# Exercise 6 – Start a Camunda 8 Process Using an API Call

**Course:** 21CSE735P – Workflow Automation Engineering · **Tools:** Camunda Modeler, Camunda 8 Run (C8 Run), Postman, Operate · **Mode:** Individual

## Task

Create a simple **Leave Request** process in Camunda 8 and start it with a REST API call from Postman.

## Files

| File | Purpose |
|---|---|
| `leave-request.bpmn` | Start Event → User Task **Approve Leave** → End Event, Process ID `leave-request` |
| `postman/Exercise6-Start-Leave-Request.postman_collection.json` | Postman requests: topology check, start instance, wrong process ID (viva Q2), get instance |

## Steps

1. **Model** – open `leave-request.bpmn` in Camunda Modeler. Process ID = `leave-request`, *Executable* is ticked, the user task is a Camunda user task.
2. **Deploy** – start C8 Run (`./start.sh` or `c8run.exe start`), then in Modeler click **Deploy** (rocket icon) → *Camunda 8 Self-Managed* → cluster endpoint `http://localhost:26500` (or REST `http://localhost:8080`), authentication *None*.
3. **Start an instance from Postman** – import the collection, then send:

   ```http
   POST http://localhost:8080/v2/process-instances
   Content-Type: application/json

   {
     "processDefinitionId": "leave-request",
     "variables": {
       "employeeName": "Ravi",
       "days": 3
     }
   }
   ```

   The response contains `processDefinitionKey`, `processDefinitionVersion` and the **`processInstanceKey`** of the new instance (note it down – it appears in Operate).
4. **Verify in Operate** – open `http://localhost:8080/operate` (login `demo` / `demo`) → *Processes* → **Leave Request** → open the instance with that key. The token waits at **Approve Leave** and the Variables panel shows `employeeName = "Ravi"` and `days = 3`.

## Screenshots to submit

Save them in `screenshots/`:

- [ ] `01-bpmn-diagram.png` – the diagram in Modeler with Process ID `leave-request` visible
- [ ] `02-postman-request-response.png` – Postman request body and the 200 response showing `processInstanceKey`
- [ ] `03-operate-running-instance.png` – Operate instance view: token at *Approve Leave*, variables `employeeName` and `days`

## Viva questions

**1. What is the difference between `processDefinitionId` and `processInstanceKey`?**

`processDefinitionId` is the BPMN process ID you set in the model (`leave-request`). It names the *definition* – the blueprint – and stays the same across every deployed version. `processInstanceKey` is a unique numeric key the engine generates for *one running execution* of that definition. One definition can have many instances, each with its own key; you use the key to find, inspect or cancel that specific instance (for example in Operate or `GET /v2/process-instances/{processInstanceKey}`).

**2. What response do you get if the Process ID is spelled wrong?**

The engine rejects the create command because no deployed definition has that ID. The REST API returns **HTTP 404 Not Found** with a problem-detail body similar to:

```json
{
  "type": "about:blank",
  "title": "NOT_FOUND",
  "status": 404,
  "detail": "Command 'CREATE' rejected with code 'NOT_FOUND': Expected to find process definition with process ID 'leave-requst', but none found",
  "instance": "/v2/process-instances"
}
```

No instance is created, so nothing new appears in Operate. (Try the *Viva Q2 – wrong process ID* request in the collection.)

**3. Start the process 3 times. How many instances appear in Operate?**

Three separate instances (plus any started earlier). Each call creates a new instance with its own `processInstanceKey`, and all of them stay **active**, waiting at *Approve Leave*, until someone completes the user task in Tasklist.
