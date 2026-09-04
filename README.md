
# disa-returns-submission


### Before running the app

This repository relies on having mongodb running locally. You can start it with:

```bash
# first check to see if mongo is already running
docker ps | grep mongodb

# if not, start it
docker run --restart unless-stopped --name mongodb -p 27017:27017 -d percona/percona-server-mongodb:7.0 --replSet rs0
```

Reference instructions for [setting up docker](https://docs.tax.service.gov.uk/mdtp-handbook/documentation/developer-set-up/install-docker.html) and [running mongodb](https://docs.tax.service.gov.uk/mdtp-handbook/documentation/developer-set-up/set-up-mongodb.html#install-mongodb-applesilicon-mac).

## Running the app

### Service manager
The whole service can be started with:
```bash
sm2 --start DISA_RETURNS_ALL
```

### Locally

```bash
sbt run
```

To run locally with HMRC-style test-only routes enabled:

```bash
sbt run -Dapplication.router=testOnlyDoNotUseInAppConf.Routes
```

If starting through service-manager, pass the same JVM parameter in the local service profile:

```bash
-Dapplication.router=testOnlyDoNotUseInAppConf.Routes
```

The following test-only routes are available only with that router:

- `GET /disa-returns-submission/test-only/overrides/:zReference`
- `PUT /disa-returns-submission/test-only/overrides/:zReference`
- `DELETE /disa-returns-submission/test-only/overrides/:zReference`
- `POST /disa-returns-submission/test-only/monthly-returns`

Production monthly-return and reporting-window routes require an internal-auth token. Local Bruno requests use:

```text
Authorization: valid-internal-auth-token-disa-returns-backend
```

That token must exist in local internal-auth with `READ` and `WRITE` permissions for `disa-returns-submission/*`.
The test-only routes above remain unauthenticated.

Enabling this router also changes dependency bindings. `TimeSource` and `ReportingWindowService` are bound to
aggregate-backed test implementations. Production monthly-return and reporting-window
status routes therefore observe test-only clock and reporting-window overrides while this router is active. With the
normal router, they use `SystemClock` and the configured declaration-period boundaries only and never query overrides.

Test-only utilities trim and uppercase Z-references before validation. A valid normalized reference is `Z` followed
by exactly four digits. Persisted overrides are keyed by that value, so `z1234` and `Z1234` address the same state.

The production reporting-window status route remains available for the effective status of a normalized Z-reference:

```text
GET /disa-returns-submission/reporting-window/status/:zReference
```

It requires the `READ` permission, returns `200 OK` with a `reportingWindowOpen` boolean for a valid Z-reference, and
returns `400 Bad Request` for an invalid Z-reference. With the test-only router it observes the aggregate override.

### Aggregate overrides

Use `PUT /disa-returns-submission/test-only/overrides/:zReference` to atomically replace all overrides for one normalized
Z-reference:

```json
{
  "clock": {
    "date": "2026-05-17"
  },
  "reportingWindow": {
    "startDate": "2026-05-16T23:59:00Z",
    "endDate": "2026-05-17T00:01:00Z"
  }
}
```

Both fields are optional. Because PUT is a full replacement, omitting either field clears that field; `{}` clears both.
The clock date is applied at `00:00:00Z`. The reporting-window interval is inclusive and `startDate` must be before or
equal to `endDate`. Invalid references or bodies return `400`.

GET, successful PUT, and DELETE always return `200` with this flat shape:

```json
{
  "zReference": "Z1234",
  "clock": { "date": "2026-05-17" },
  "reportingWindow": {
    "startDate": "2026-05-16T23:59:00Z",
    "endDate": "2026-05-17T00:01:00Z"
  }
}
```

Absent fields are returned as `null`, and DELETE removes the complete aggregate and returns both fields as `null`.
The override endpoint only reports configured overrides; use the production reporting-window status endpoint for the
effective status. Submission processing uses `SystemClock` when the aggregate clock is absent or expired, and the
configured declaration-period boundaries when its reporting window is absent. The complete persistence document is
keyed by normalized Z-reference in one `testOverrides` MongoDB collection and expires after `testOverrideTtlHours`,
which defaults to one hour. Repository reads reject expired documents immediately, before MongoDB's TTL cleanup runs.

Monthly-return creation and declaration resolve the effective instant and reporting-window state once, then use that
same instant for both previous-month validation and window evaluation.

### Submission and declaration contract

`POST /disa-returns-submission/monthly/:zReference/:taxYear/:month/declarations` returns `422` with code
`MONTHLY_RETURN_ALREADY_DECLARED` for a duplicate declaration, or `DECLARATION_PERIOD_CLOSED` when the reporting window
is closed. These code values form the backend mapping contract.

Declarations can register submissions whose data is still being transferred:

```json
{
  "nilReturn": false,
  "pendingSubmissionIds": [
    "consumer-generated-submission-id"
  ]
}
```

`pendingSubmissionIds` is optional and defaults to an empty list. It cannot be supplied when `nilReturn` is `true`.
Pending submissions are recorded with `CREATED` status.

Submission data is transferred with:

```text
PUT /disa-returns-submission/monthly/:zReference/:taxYear/:month/submissions/:submissionId
Content-Type: application/x-ndjson
```

A successful PUT updates an existing `CREATED` submission, or creates a missing submission before declaration, with
`STORED` status. A `CREATED` submission remains uploadable after declaration. An already `STORED` submission, or an
unknown submission after declaration, returns `409 Conflict`.

For example, set the clock to `2026-06-17` to create and declare May 2026 monthly returns inside the configured declaration period, or `2026-06-20` to test declaration attempts outside the configured declaration period.

Use `POST` to clear monthly returns for specific Z-references from the submission service local database:

```bash
curl -X POST \
  -H 'Content-Type: application/json' \
  -d '{"zReferences":["Z1234","Z5678"]}' \
  http://localhost:12103/disa-returns-submission/test-only/monthly-returns
```

`zReferences` must be a non-empty JSON array. References are trimmed, uppercased, and de-duplicated; any invalid
reference returns `400` without deleting records. The route deletes only returns belonging to the supplied references,
returns `204` on success and `503` on repository failure, and must not run concurrently with return creation for those
references.

You can then query the app to ensure it is working with the following command:

```bash
# other useful commands
sbt clean

sbt reload

sbt compile
```

### Running the test suite

To run the unit tests:

```bash
sbt test
```

To run the integration tests:

```bash
sbt it/test
```

### Test-Only Endpoints

Test-only endpoints require the service to be running with `-Dapplication.router=testOnlyDoNotUseInAppConf.Routes`.
Otherwise the routes will not be available.

| Endpoint | Used by | Purpose and response |
| --- | --- | --- |
| `GET /disa-returns-submission/test-only/overrides/:zReference` | `bruno/TestOnly/Overrides` | Return the configured aggregate fields. |
| `PUT /disa-returns-submission/test-only/overrides/:zReference` | `bruno/TestOnly/Overrides` | Atomically replace and return the aggregate. |
| `DELETE /disa-returns-submission/test-only/overrides/:zReference` | `bruno/TestOnly/Overrides` | Delete the aggregate and return empty options. |
| `POST /disa-returns-submission/test-only/monthly-returns` | `bruno/TestOnly/MonthlyReturns`, performance tests | Delete monthly returns only for supplied normalized, de-duplicated references and return `204`; reject missing, empty, or invalid input with `400`. |

The `ReportingWindow/01-200-status-open-by-override` Bruno journey creates one aggregate, verifies the production status
route, and deletes the aggregate. The test-only override folder covers GET, full replacement, omitted-field clearing,
DELETE, and invalid interval behavior.

### Before you commit

This service leverages scalaFmt to ensure that the code is formatted correctly.

Before you commit, please run the following commands to check that the code is formatted correctly:

```bash
# formats all source files, runs unit and integration tests, and produces a coverage report
sbt precommit

# checks all source and sbt files are correctly formatted
sbt prePrChecks

# if checks fail, you can format with the following commands

# formats all source files
sbt scalafmtAll

# formats all sbt files
sbt scalafmtSbt

# formats just the main source files (excludes test and configuration files)
sbt scalafmt
```

### License

This code is open source software licensed under the [Apache 2.0 License](https://www.apache.org/licenses/LICENSE-2.0.html).
