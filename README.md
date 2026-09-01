
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

- `GET /disa-returns-submission/test-only/clock/:zReference`
- `PUT /disa-returns-submission/test-only/clock/:zReference/yyyy-MM-dd`
- `DELETE /disa-returns-submission/test-only/clock/:zReference`
- `POST /disa-returns-submission/test-only/monthly-returns`
- `PUT /disa-returns-submission/test-only/reporting-window-override/:zReference`
- `DELETE /disa-returns-submission/test-only/reporting-window-override`

Production monthly-return and reporting-window routes require an internal-auth token. Local Bruno requests use:

```text
Authorization: valid-internal-auth-token-disa-returns-backend
```

That token must exist in local internal-auth with `READ` and `WRITE` permissions for `disa-returns-submission/*`.
The test-only routes above remain unauthenticated.

The production reporting-window route returns whether the window is open for a normalized Z-reference:

```text
GET /disa-returns-submission/reporting-window/status/:zReference
```

It requires the `READ` permission, returns `200 OK` with a `reportingWindowOpen` boolean for a valid Z-reference, and
returns `400 Bad Request` for an invalid Z-reference.

Monthly-return and reporting-window override deletion accept `Content-Type: application/json` with a non-empty sequence
of Z-references:

```json
{
  "zReferences": ["Z1234", "Z5678"]
}
```

Both endpoints normalize case, remove duplicate Z-references, and return `204 No Content` after deleting only the
supplied records. A missing, empty, or invalid `zReferences` value returns `400 Bad Request`.

When test-only routes are enabled, a Z-reference-specific override stored through the test-only endpoint takes
precedence over the configured declaration period. Without the test-only router, the service uses only
`declarationPeriodStart` and `declarationPeriodEnd`.

The override request has the same body as the stubs endpoint:

```json
{
  "startDate": "2026-05-16T23:59:00Z",
  "endDate": "2026-05-17T00:01:00Z"
}
```

Overrides are keyed by normalized Z-reference in MongoDB and expire after `reportingWindowOverrideTtlHours`.

### Submission and declaration contract

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

Use `GET` to inspect the app clock:

```bash
curl http://localhost:12103/disa-returns-submission/test-only/clock/Z1234
```

Use `PUT` to set the app date for one Z-reference during declaration-period testing. The date must be in `yyyy-MM-dd` format and is applied at `00:00:00Z`:

```bash
curl -X PUT http://localhost:12103/disa-returns-submission/test-only/clock/Z1234/2026-05-17
```

Clock overrides are stored in MongoDB so they apply across service instances and expire after `clockOverrideTtlHours`.

Use `DELETE` to reset back to the system UTC clock:

```bash
curl -X DELETE http://localhost:12103/disa-returns-submission/test-only/clock/Z1234
```

For example, set the clock to `2026-05-17` to create and declare May 2026 monthly returns inside the configured declaration period, or `2026-05-20` to test declaration attempts outside the configured declaration period.

Use `POST` to clear monthly returns for specific Z-references from the submission service local database:

```bash
curl -X POST \
  -H 'Content-Type: application/json' \
  -d '{"zReferences":["Z1234","Z5678"]}' \
  http://localhost:12103/disa-returns-submission/test-only/monthly-returns
```

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

| Endpoint | Used by | Purpose |
| --- | --- | --- |
| `GET /disa-returns-submission/test-only/clock/:zReference` | `bruno/TestOnly/Clock` | Inspect the time source used for one Z-reference. |
| `PUT /disa-returns-submission/test-only/clock/:zReference/:date` | `bruno/TestOnly/Clock` | Set the date for one Z-reference in `yyyy-MM-dd` format. |
| `DELETE /disa-returns-submission/test-only/clock/:zReference` | `bruno/TestOnly/Clock` | Reset one Z-reference back to the system UTC clock. |
| `POST /disa-returns-submission/test-only/monthly-returns` | `bruno/TestOnly/MonthlyReturns`, performance tests | Clear monthly returns for the supplied normalized Z-references. This must not run concurrently with return creation for those Z-references. |
| `PUT /disa-returns-submission/test-only/reporting-window-override/:zReference` | `bruno/TestOnly/ReportingWindowOverride`, test automation | Store a temporary reporting-window override for one normalized Z-reference. |
| `DELETE /disa-returns-submission/test-only/reporting-window-override` | `bruno/TestOnly/ReportingWindowOverride`, performance tests, API tests | Remove reporting-window overrides for the supplied normalized Z-references. |

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

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html").
