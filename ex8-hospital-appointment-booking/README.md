# Exercise 8 – Hospital Appointment Booking (28 Oct 2026)

Camunda 8 model set for a hospital appointment-booking process with a **reusable child process**, a **DMN slot decision**, **call activities with input/output mappings**, and a **cancellation event subprocess**.

## Files

| File | What it is |
|---|---|
| `send-notification.bpmn` | Child process `send-notification`: start → **REST Outbound Connector** (Telegram `sendMessage`) → end |
| `slot_decision.dmn` | Decision `slot_decision`, hit policy **UNIQUE** |
| `hospital-appointment-booking.bpmn` | Parent process `hospital-appointment-booking` |
| `postman/Exercise8-Hospital-Booking.postman_collection.json` | Requests for the three test cases + the cancellation message |

## 1. Child process – `send-notification`

* Inputs: `recipientId` (Telegram chat id), `messageText`.
* Task **Send Telegram message** is a REST Outbound Connector (`io.camunda:http-json:1`):
  * `POST https://api.telegram.org/bot{{secrets.TELEGRAM_BOT_TOKEN}}/sendMessage`
  * Body: `={chat_id: recipientId, text: messageText}`
  * Result expression: `={notificationSent: response.status = 200, telegramMessageId: response.body.result.message_id}`
* Output: `notificationSent` (boolean).

## 2. DMN – `slot_decision` (hit policy UNIQUE)

Inputs: `department` (string), `bookedCount` (number). Outputs: `slotStatus` (`AVAILABLE` / `FULL`), `suggestedAction`.

| # | department | bookedCount | slotStatus | suggestedAction |
|---|---|---|---|---|
| 1 | "Cardiology" | >= 10 | FULL | Offer the next available Cardiology slot or add the patient to the waitlist |
| 2 | "Cardiology" | < 10 | AVAILABLE | Book the requested slot |
| 3 | "Orthopedics" | >= 8 | FULL | Offer the next available Orthopedics slot |
| 4 | "Orthopedics" | < 8 | AVAILABLE | Book the requested slot |
| 5 | "Neurology" | >= 6 | FULL | Offer the next available Neurology slot |
| 6 | "Neurology" | < 6 | AVAILABLE | Book the requested slot |
| 7 | "General Medicine" | >= 20 | FULL | Offer the next available General Medicine slot |
| 8 | "General Medicine" | < 20 | AVAILABLE | Book the requested slot |
| 9 | any other department | >= 15 | FULL | Offer the next available slot in the same department |
| 10 | any other department | < 15 | AVAILABLE | Book the requested slot |

Exactly one rule matches every input, which is what UNIQUE requires.

## 3. Parent process – `hospital-appointment-booking`

```
Booking requested → [Check slot] (Business Rule Task → slot_decision, result: slotResult)
                  → <Slot available?>
                       AVAILABLE: [Book slot] → [[Send booking confirmation]] → (timer: wait until appointment) → Appointment completed
                       FULL:      [[Send slot-full notice]] → Slot full – not booked

Event subprocess "Handle cancellation" (interrupting message start "cancel-booking", correlation key = patientId):
    Cancellation requested → [Release slot] → [[Send cancellation notice]] → Booking cancelled
```

* Gateway conditions: `=slotResult.slotStatus = "AVAILABLE"` and `=slotResult.slotStatus = "FULL"`.
* **Book slot** and **Release slot** are FEEL script tasks (results `booking` and `cancellation`), so no Java worker is needed.
* After the confirmation, the booking stays "in progress" at a 10-minute timer (stand-in for the appointment time). That gives you a window to send the cancellation message for test case 3.
* The message start event is **interrupting**: the cancellation stops the main booking flow, releases the slot and notifies the patient.

## 4. Input and output mappings on the call activities

Final setting on all three call activities: **Propagate all parent variables = off**, **Propagate all child variables = off**, with explicit mappings:

| Call activity | Inputs (parent → child) | Output (child → parent) |
|---|---|---|
| Send booking confirmation | `recipientId = telegramChatId`, `messageText = "Hello … is confirmed. Booking ID: " + booking.bookingId` | `confirmationSent = notificationSent` |
| Send slot-full notice | `recipientId = telegramChatId`, `messageText = "Sorry … is full. " + slotResult.suggestedAction` | `slotFullNoticeSent = notificationSent` |
| Send cancellation notice | `recipientId = telegramChatId`, `messageText = "… has been cancelled and the slot has been released."` | `cancellationNoticeSent = notificationSent` |

### Comparing the two behaviours

To see the difference, first run with both toggles **on** (Modeler → call activity → *Called element* → tick *Propagate all parent variables* and *Propagate all child variables*), then switch them **off** again.

| | Propagation ON | Propagation OFF + mappings (final) |
|---|---|---|
| Variables in the child instance | Every parent variable (`patientId`, `patientName`, `department`, `preferredSlot`, `bookedCount`, `telegramChatId`, `slotResult`, `booking`, …) **plus** `recipientId`, `messageText` | Only `recipientId` and `messageText` |
| Variables copied back to the parent | Every child variable (`recipientId`, `messageText`, `notificationSent`, `telegramMessageId`) | Only the mapped output, e.g. `confirmationSent` |
| Effect | Child sees data it does not need; the parent scope gets cluttered and values such as `messageText` / `notificationSent` are overwritten each time the child is called | Clear contract between parent and child; the child is genuinely reusable and cannot overwrite parent variables by accident |

Check this in Operate: open the child instance from the call activity and compare its **Variables** panel in both runs.

## 5. Setup

1. **Telegram bot**: in Telegram, open *@BotFather* → `/newbot` → copy the bot token. Send any message to your bot, then open `https://api.telegram.org/bot<token>/getUpdates` and copy `message.chat.id` – this is `telegramChatId`.
2. **Connector secret** `TELEGRAM_BOT_TOKEN`:
   * Camunda 8 Run (local): add `TELEGRAM_BOT_TOKEN=<token>` to the `.env` file in the c8run folder (or set it as an environment variable) and restart C8 Run.
   * SaaS: Console → your cluster → **Connector secrets** → create `TELEGRAM_BOT_TOKEN`.
   * Never commit the token to Git.
3. **Deploy** from Camunda Modeler in this order: `send-notification.bpmn`, `slot_decision.dmn`, `hospital-appointment-booking.bpmn`.

## 6. Deploy and test

Start instances from Modeler (*Start instance* with variables) or with the Postman collection in `postman/`.

| Case | Start variables | Expected result |
|---|---|---|
| 1. Booking succeeds | `{"patientId":"P1001","patientName":"Arun","department":"Cardiology","preferredSlot":"2026-10-30 10:00","bookedCount":3,"telegramChatId":"<chat id>"}` | `slotResult.slotStatus = AVAILABLE` → Book slot → confirmation child instance → waits at timer; Telegram confirmation received |
| 2. Slot full | same, `"patientId":"P1002"`, `"bookedCount":12` | `slotStatus = FULL` → slot-full child instance → ends at *Slot full – not booked* |
| 3. Booking + cancellation | start with `"patientId":"P1003"`, `"bookedCount":4`; while it waits at the timer publish the message below | Main flow interrupted → Release slot → cancellation child instance → *Booking cancelled* |

Cancellation message (REST):

```http
POST http://localhost:8080/v2/messages/publication
Content-Type: application/json

{ "name": "cancel-booking", "correlationKey": "P1003", "timeToLive": 0 }
```

Starting an instance (REST):

```http
POST http://localhost:8080/v2/process-instances
Content-Type: application/json

{ "processDefinitionId": "hospital-appointment-booking",
  "variables": { "patientId": "P1001", "patientName": "Arun", "department": "Cardiology",
                 "preferredSlot": "2026-10-30 10:00", "bookedCount": 3, "telegramChatId": "<chat id>" } }
```

## 7. Screenshots to add (Operate)

Save them in `screenshots/` in this folder:

- [ ] `01-parent-booking-success.png` – parent instance for case 1 (path through *Book slot*, waiting at timer), Variables panel showing `slotResult` and `confirmationSent`
- [ ] `02-child-instance.png` – the `send-notification` child instance opened from the call activity link
- [ ] `03-slot-full.png` – parent instance for case 2 ending at *Slot full – not booked*
- [ ] `04-cancellation-path.png` – parent instance for case 3 showing the event subprocess path
- [ ] `05-propagation-on-vs-off.png` – child Variables panel with propagation on vs off
- [ ] `06-telegram.png` – the Telegram messages received
