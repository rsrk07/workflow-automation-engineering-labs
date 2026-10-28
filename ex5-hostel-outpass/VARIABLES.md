# Process variables – Hostel Out-Pass Request

The REST endpoint `POST /outpass` starts `hostel-outpass` with the request body as process variables: `studentName`, `regNo`, `hostelBlock`, `outDate`, `returnDate`, `parentPhone`, `purpose` (dates in `yyyy-MM-dd`).

| Worker (job type) | Reads | Writes |
|---|---|---|
| **check-outpass** – *Check request* | `regNo`, `outDate`, `returnDate` | `nights` (days between out and return date), `valid` (false if the return date is before the out date), `approved` (true only for a valid day out, i.e. `nights = 0`) |
| **issue-outpass** – *Issue out-pass* | `regNo`, `outDate`, `studentName`, `parentPhone`, `approved`, `wardenRemarks` | `passStatus` (`ISSUED` or `REJECTED`), `passNumber` (`OP-<regNo>-<outDate>`, only when issued) |

The **Warden approval** form (and the escalated **Chief Warden approval** form) write `approved` (checkbox) and `wardenRemarks` (text area), overwriting the `approved = false` set by `check-outpass` for overnight requests.

How the variables drive the gateway after *Check request*:

* `=valid and nights = 0` → day out, goes straight to *Issue out-pass* with `approved = true`.
* `=valid and nights >= 1` → *Warden approval* user task; the warden's checkbox decides `approved`.
* `=not(valid)` → invalid dates, goes to *Issue out-pass* with `approved = false`, so `passStatus = REJECTED` without the warden.

`issue-outpass` therefore only has to look at `approved`: every path guarantees it is set. It logs an "SMS to parent" line with the decision, and the instance ends with `passStatus = ISSUED` or `REJECTED` in Operate.
