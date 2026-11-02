/*
 * Copyright 2026 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.disareturnssubmission.models.monthly

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

import java.time.Instant

class MonthlyReturnReportingPeriodSpec extends AnyFreeSpec with Matchers {

  "MonthlyReturnReportingPeriod.previousMonthOf" - {

    "must return the previous month in the same tax year" in {
      MonthlyReturnReportingPeriod.previousMonthOf(Instant.parse("2026-06-17T12:00:00Z")) mustBe
        MonthlyReturnReportingPeriod("2026-27", 5)
    }

    "must return April as the first month of the new tax year when run in May" in {
      MonthlyReturnReportingPeriod.previousMonthOf(Instant.parse("2026-05-10T00:00:00Z")) mustBe
        MonthlyReturnReportingPeriod("2026-27", 4)
    }

    "must return March in the previous tax year when run in April" in {
      MonthlyReturnReportingPeriod.previousMonthOf(Instant.parse("2026-04-10T00:00:00Z")) mustBe
        MonthlyReturnReportingPeriod("2025-26", 3)
    }

    "must return December of the previous calendar year when run in January" in {
      MonthlyReturnReportingPeriod.previousMonthOf(Instant.parse("2027-01-10T00:00:00Z")) mustBe
        MonthlyReturnReportingPeriod("2026-27", 12)
    }

    "must use UTC to determine the current month" in {
      MonthlyReturnReportingPeriod.previousMonthOf(Instant.parse("2026-06-30T23:59:59Z")) mustBe
        MonthlyReturnReportingPeriod("2026-27", 5)
      MonthlyReturnReportingPeriod.previousMonthOf(Instant.parse("2026-07-01T00:00:00Z")) mustBe
        MonthlyReturnReportingPeriod("2026-27", 6)
    }
  }
}
