# Exercise 5 – Hostel Out-Pass Request (Camunda 8 SaaS + Spring Boot)

SRM hostel students need an out-pass to leave campus. This project automates the request with a **Camunda 8 SaaS** cluster and a **Spring Boot** app, following the *Student Leave Workflow* lab pattern.

## What's here

```
ex5-hostel-outpass/
├─ README.md                      this file
├─ VARIABLES.md                   half-page note: which variables each worker reads and writes
└─ outpass-workflow/              Spring Boot project (Maven)
   ├─ pom.xml                     Spring Boot 3.5.6 + camunda-spring-boot-starter 8.8.15
   ├─ requests.http               the three test requests (also usable as curl / Postman bodies)
   └─ src/main/
      ├─ java/edu/srmist/outpass/
      │  ├─ OutpassWorkflowApplication.java   @SpringBootApplication + @Deployment
      │  ├─ api/OutpassController.java        POST /outpass
      │  ├─ api/OutpassRequest.java           request body
      │  └─ worker/OutpassWorkers.java        check-outpass, issue-outpass
      └─ resources/
         ├─ application.yaml                  SaaS connection (values from environment variables)
         └─ bpmn/
            ├─ hostel-outpass.bpmn            process model
            ├─ warden-approval.form           Warden form
            └─ chief-warden-approval.form     escalation form (bonus)
```

## Process model – `hostel-outpass`

```
Out-pass requested → [Check request] (check-outpass) → <Request valid?>
     =valid and nights = 0   (day out) ───────────────────────────────┐
     =valid and nights >= 1  → [Warden approval] (form) ──────────────┤
                                 └─(timer 24 h)→ [Chief Warden approval] ┤ → <Merge> → [Issue out-pass] (issue-outpass) → Out-pass processed
     =not(valid)             (invalid dates) ──────────────────────────┘
```

| In the model | Value | Matches in code |
|---|---|---|
| Process ID | `hostel-outpass` | `bpmnProcessId("hostel-outpass")` in `OutpassController` |
| Task type | `check-outpass` | `@JobWorker(type = "check-outpass")` |
| Task type | `issue-outpass` | `@JobWorker(type = "issue-outpass")` |
| Form ID | `warden-approval-form` | `bpmn/warden-approval.form` in `@Deployment` |
| Form ID (bonus) | `chief-warden-approval-form` | `bpmn/chief-warden-approval.form` in `@Deployment` |
| Form field keys | `approved`, `wardenRemarks` | `@Variable` names in `issueOutpass` |

**Bonus (extension):** an interrupting **timer boundary event (PT24H)** on *Warden approval* escalates the request to a *Chief Warden approval* user task if the warden does not act within 24 hours.

## How to run

### A. Camunda Console (browser)

1. Log in at [console.camunda.io](https://console.camunda.io) → **Clusters → Create new cluster** (e.g. `outpass-demo`, nearest region, 8.8) → wait for **Healthy**.
2. Open the cluster → **API** → **Create new client** → name `outpass-app`, tick **Zeebe / Orchestration Cluster** → copy **Client ID, Client Secret, Cluster ID, Region** (Spring Boot tab).
3. (Optional) Web Modeler → project *Hostel Out-Pass* → **Upload files** → the `.bpmn` and both `.form` files from `src/main/resources/bpmn/`. No need to deploy from Modeler – the app deploys them on start-up.

### B. Laptop

Requires JDK 17+, Maven 3.9+.

```powershell
# Windows PowerShell – keep the secret out of Git by using environment variables
$env:CAMUNDA_CLIENT_ID="<client id>"
$env:CAMUNDA_CLIENT_SECRET="<client secret>"
$env:CAMUNDA_CLUSTER_ID="<cluster id>"
$env:CAMUNDA_CLUSTER_REGION="<region, e.g. bru-2>"
cd outpass-workflow
mvn spring-boot:run
```

(macOS/Linux: `export CAMUNDA_CLIENT_ID=...` etc.)

The log shows the deployment of `hostel-outpass` and the two workers opening. Set `camunda.version` in `pom.xml` to the same minor version as your cluster if it is not 8.8.

### C. Test the three cases

Send the requests in `outpass-workflow/requests.http` (IntelliJ / VS Code REST Client), or with curl / Postman:

```bash
curl -X POST http://localhost:8080/outpass -H "Content-Type: application/json" \
  -d '{"studentName":"Priya","regNo":"RA2311003010001","hostelBlock":"Paari A","outDate":"2026-11-07","returnDate":"2026-11-07","parentPhone":"+91-9800000001","purpose":"Shopping"}'
```

| Case | Dates | Expected |
|---|---|---|
| 1. Auto-approved (day out) | `outDate = returnDate` | Instance completes; `passStatus = ISSUED`, `passNumber = OP-RA2311003010001-2026-11-07`; log prints *SMS to parent … ISSUED* |
| 2. Warden-approved | 2 nights | Token waits at **Warden approval** → Tasklist → open task → *Assign to me* → tick **Approve this out-pass**, add a remark → **Complete task** → `passStatus = ISSUED` |
| 3. Rejected | `returnDate` before `outDate` | Gateway takes *Invalid dates*; `passStatus = REJECTED` without the warden |

## Screenshots to add

Save in `screenshots/` (same folder as this README):

- [ ] `01-operate-auto-approved.png` – Operate: case 1 instance, path + Variables (`nights = 0`, `passStatus = ISSUED`)
- [ ] `02-tasklist-warden-form.png` – Tasklist with the Warden approval form open
- [ ] `03-operate-warden-approved.png` – Operate: case 2 instance after approval
- [ ] `04-operate-rejected.png` – Operate: case 3 instance on the *Invalid dates* path (`passStatus = REJECTED`)
- [ ] `05-app-log.png` – the Spring Boot log with the *SMS to parent* lines

## Marking scheme coverage

| Component | Where |
|---|---|
| BPMN model correct: IDs, task types, gateway conditions (3) | `hostel-outpass.bpmn` – table above |
| Job workers implemented and connected to SaaS (3) | `OutpassWorkers.java`, `application.yaml` (mode `saas`) |
| REST endpoint starts the process (1) | `OutpassController.java` – `POST /outpass` |
| Warden form linked and working in Tasklist (1) | `warden-approval.form`, linked on *Warden approval* |
| Three test cases in Operate + variable note (2) | `requests.http`, screenshots, `VARIABLES.md` |
| Bonus: timer escalation to Chief Warden (2) | Timer boundary `PT24H` → *Chief Warden approval* |

## Troubleshooting

| Symptom | Fix |
|---|---|
| 401 / token error at start-up | Wrong client ID/secret, or the client lacks the Zeebe / Orchestration Cluster scope. Recreate the client. |
| UNAVAILABLE / cannot connect | Wrong cluster ID or region. In SaaS mode do not set grpc/rest addresses by hand. |
| Instance stuck at a service task | Worker type does not match the task type (check spelling/case). |
| Incident "Text cannot be parsed" | Date not in `yyyy-MM-dd`. Fix the variable in Operate, then Retry. |
| User task has no form | Form file missing from `@Deployment`, or form ID not linked. |
