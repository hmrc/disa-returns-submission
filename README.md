
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

Test-only clock routes are available only with that router:

- `GET /disa-returns-submission/test-only/clock`
- `PUT /disa-returns-submission/test-only/clock/yyyy-MM-dd`
- `DELETE /disa-returns-submission/test-only/clock`
- `DELETE /disa-returns-submission/test-only/monthly-returns`

Use `GET` to inspect the app clock:

```bash
curl http://localhost:12103/disa-returns-submission/test-only/clock
```

Use `PUT` to set the app date for declaration-period testing. The date must be in `yyyy-MM-dd` format and is applied at `00:00:00Z`:

```bash
curl -X PUT http://localhost:12103/disa-returns-submission/test-only/clock/2026-05-17
```

Use `DELETE` to reset back to the system UTC clock:

```bash
curl -X DELETE http://localhost:12103/disa-returns-submission/test-only/clock
```

For example, set the clock to `2026-05-17` to create and declare May 2026 monthly returns inside the configured declaration period, or `2026-05-20` to test declaration attempts outside the configured declaration period.

Use `DELETE` to clear all monthly returns from the submission service local database:

```bash
curl -X DELETE http://localhost:12103/disa-returns-submission/test-only/monthly-returns
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
| `GET /disa-returns-submission/test-only/clock` | `bruno/TestOnly/Clock` | Inspect the app clock used by declaration-period logic. |
| `PUT /disa-returns-submission/test-only/clock/:date` | `bruno/TestOnly/Clock` | Set the app date in `yyyy-MM-dd` format for declaration-period testing. |
| `DELETE /disa-returns-submission/test-only/clock` | `bruno/TestOnly/Clock` | Reset the app clock back to the system UTC clock. |
| `DELETE /disa-returns-submission/test-only/monthly-returns` | `bruno/TestOnly/MonthlyReturns` | Clear all monthly returns from the submission service local database. |

### Before you commit

This service leverages scalaFmt to ensure that the code is formatted correctly.

Before you commit, please run the following commands to check that the code is formatted correctly:

```bash
# runs a scala format check, runs unit tests, runs integration tests and produces a coverage report.
sbt runAllChecks

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
