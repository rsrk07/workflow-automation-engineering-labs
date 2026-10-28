# Exercise 7 – Leave Application System (Full-stack Implementation, 21 Oct 2026)

Camunda 8 • React • Spring Boot • Docker

An employee applies for leave in a React portal; Spring Boot stores the request and starts a Camunda 8 process; a job worker validates dates and balance; a manager approves or rejects from the portal (or Tasklist); a second worker finalises the outcome and updates the annual leave balance.

```
Browser (React, :5173 / :3000) ──/api──▶ Spring Boot (:8081) ──▶ Camunda 8 (REST :8080, gRPC :26500)
                                             │   ▲  job workers: validate-leave, finalize-leave
                                             ▼   │
                                         H2 (dev) / PostgreSQL (Docker)
```

The browser talks only to the Spring Boot API; Spring Boot starts process instances and completes user tasks through Camunda.

## Folder layout

```
ex7-leave-application-system/
├─ bpmn/
│  ├─ leave-request.bpmn        Step 1 – process model (Process ID leave-request)
│  └─ leave-approval.form       Step 1 – manager approval form (form ID leave-approval)
├─ frontend/                    Step 2 – React + TypeScript (Vite)
│  ├─ vite.config.ts            proxies /api → http://localhost:8081
│  ├─ src/api.ts                API client + types
│  ├─ src/App.tsx               Apply / My requests / Manager inbox tabs
│  ├─ src/ApplyForm.tsx, MyRequests.tsx, ManagerInbox.tsx, App.css
│  ├─ Dockerfile, nginx.conf    Step 5 – multi-stage build served by Nginx
├─ backend/                     Step 3 – Spring Boot 3.5 + Java 21 + Camunda starter
│  ├─ pom.xml
│  ├─ src/main/resources/application.yml, bpmn/ (copies of the Step 1 files)
│  ├─ src/main/java/com/example/leave/
│  │  ├─ LeaveApplication.java              @Deployment of bpmn/**/*.bpmn and *.form
│  │  ├─ domain/  LeaveStatus, LeaveRequest, LeaveBalance, repositories
│  │  ├─ api/     SubmitLeaveRequest, DecisionRequest, LeaveController
│  │  ├─ service/ LeaveService               submit, validate, decide, finalize, balance
│  │  └─ worker/  LeaveWorkers               validate-leave, finalize-leave
│  └─ Dockerfile
└─ docker-compose.yml           Step 5 – frontend (3000), backend (8081), PostgreSQL
```

## Step 1 – BPMN (`bpmn/leave-request.bpmn`)

| # | Element | Name | Configuration |
|---|---|---|---|
| 1 | Start event | Leave requested | – |
| 2 | Service task | Validate leave request | type `validate-leave` |
| 3 | Exclusive gateway | Request valid? | `=valid` → Manager approval; default → Merge |
| 4 | User task | Manager approval | Camunda user task, form `leave-approval` |
| 5 | Exclusive gateway | Approved? | `=approved` → Finalize as approved; default → Merge |
| 6 | Service task | Finalize as approved | type `finalize-leave`, input `outcome = "APPROVED"` |
| 7 | Exclusive gateway | Merge | joins the invalid path and the manager-rejection path |
| 8 | Service task | Finalize as rejected | type `finalize-leave`, input `outcome = "REJECTED"` |
| 9 | End events | Leave approved / Leave rejected | separate end events |

Form `leave-approval`: checkbox **Approve this request** (`approved`), text area **Comment** (`managerComment`).

| Variable(s) | Set by | Used by |
|---|---|---|
| `requestId`, `employeeEmail`, `leaveType`, `days`, `startDate`, `endDate` | Spring Boot when starting the process | job workers, form |
| `valid`, `validationMessage` | `validate-leave` worker | *Request valid?* gateway, rejection message |
| `approved`, `managerComment` | Manager approval (portal or Tasklist) | *Approved?* gateway, finalisation |
| `outcome` | input mapping on each finalisation task | `finalize-leave` worker |

## Step 2 – React frontend

```bash
cd frontend
npm install
npm run dev          # http://localhost:5173
```

Views: **Apply** (leave type, dates, reason), **My requests** (status + remaining annual leave, refreshes every 3 s), **Manager inbox** (approve / reject with comment; retries HTTP 409 briefly while the task index catches up).

## Step 3 – Spring Boot backend

Prerequisites: Java 21, Maven 3.9+, Camunda 8 Self-Managed / C8 Run reachable at REST `http://localhost:8080` and gRPC `http://localhost:26500`.

```bash
cd backend
mvn clean package
mvn spring-boot:run          # http://localhost:8081
```

* On start-up the app deploys `leave-request.bpmn` and `leave-approval.form` – check *Leave request* in Operate (`http://localhost:8080/operate`).
* H2 console: `http://localhost:8081/h2-console`, JDBC URL `jdbc:h2:file:./data/leavedb`, user `sa`, empty password.
* Camunda version: `pom.xml` uses `camunda-spring-boot-starter` **8.8.15** for a Camunda 8.8 cluster. For a **Camunda 8.9** cluster replace the dependency with `camunda-spring-boot-3-starter` version `8.9.x` (the Java API used here is the same).

| Method and path | Purpose |
|---|---|
| `POST /api/leaves` | Submit a request (201 + created request) |
| `GET /api/leaves?employeeEmail=…` | An employee's requests |
| `GET /api/leaves/pending` | Requests waiting for manager approval |
| `GET /api/leaves/balance?employeeEmail=…` | `{ "remainingDays": n }` (default entitlement 20) |
| `POST /api/leaves/{id}/decision` | `{ "approved": true/false, "comment": "…" }` → completes the user task (204) |

Business rules in `LeaveService`:

* Working days = Monday–Friday in the range; 0 working days → rejected ("contains no working days").
* End date before start date → rejected.
* `ANNUAL` leave larger than the remaining balance → rejected before reaching the manager.
* Balance is deducted only when an `ANNUAL` request is **approved**; finalisation is idempotent, so a retried job cannot deduct twice.
* `submit` saves the request **before** starting the process (the worker must be able to read it) and updates only `processInstanceKey` afterwards, so it never overwrites a status the worker already changed.

Quick API test (Windows Command Prompt):

```bat
curl -X POST http://localhost:8081/api/leaves ^
  -H "Content-Type: application/json" ^
  -d "{\"employeeName\":\"Alice\",\"employeeEmail\":\"alice@example.com\",\"leaveType\":\"ANNUAL\",\"startDate\":\"2026-11-02\",\"endDate\":\"2026-11-04\",\"reason\":\"Personal leave\"}"
```

## Step 4 – Integration and testing

Start in this order: **Camunda** → **Spring Boot** → **React**, then run the scenarios:

| Test | Steps | Expected result |
|---|---|---|
| A. Approved | Annual leave Mon–Wed (3 weekdays) → Manager inbox → comment → Approve | APPROVED; balance 20 → 17 |
| B. Manager rejection | Another valid request → Reject with comment | REJECTED; balance unchanged |
| C. Insufficient balance | Annual leave longer than the remaining balance (e.g. 5 weeks) | Rejected by validation before manager approval; message shown |
| D. Weekend only | Saturday → Sunday | Rejected: no working days |
| E. Tasklist approval | Valid request → complete *Manager approval* in Tasklist (`http://localhost:8080/tasklist`) | Process completes; status APPROVED |
| F. Invalid dates | End date earlier than start date via curl/Postman | Rejected; no balance deduction |

In Operate, open each instance and check the path and the variables `requestId`, `valid`, `validationMessage`, `approved`, `managerComment`, `outcome`.

## Step 5 – Docker deployment

Camunda keeps running separately (C8 Run or Camunda's own Docker Compose). Then:

```bash
docker compose up -d --build
# Portal: http://localhost:3000   Backend: http://localhost:8081
```

* `backend/Dockerfile` – Maven build stage → `eclipse-temurin:21-jre` runtime.
* `frontend/Dockerfile` – Node build stage → Nginx serving `dist/` and proxying `/api/` to `backend:8081`.
* `docker-compose.yml` – `db` (PostgreSQL 16), `backend` (reaches Camunda through `host.docker.internal:26500/8080`), `frontend` (port 3000).

## Screenshots to add

Save in `screenshots/`:

- [ ] `01-bpmn.png` – the model in Modeler
- [ ] `02-portal-apply.png`, `03-portal-my-requests.png`, `04-portal-manager-inbox.png`
- [ ] `05-operate-approved.png`, `06-operate-rejected.png` – instance paths and variables
- [ ] `07-tasklist-approval.png` – test E
- [ ] `08-docker-compose-ps.png` – containers running

## Security note

This is a learning MVP: there is no authentication or role-based authorisation, so anyone who can reach `/api/leaves/pending` or the decision endpoint could act as a manager. Do not expose it outside your machine without adding authentication, secure secrets and a persistent production database.
