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

import java.time.*

final case class MonthlyReturnReportingPeriod(taxYear: String, month: Int)

object MonthlyReturnReportingPeriod {

  def previousMonthOf(instant: Instant): MonthlyReturnReportingPeriod = {
    val period    = YearMonth.from(LocalDate.ofInstant(instant, ZoneOffset.UTC)).minusMonths(1)
    val startYear = if (period.getMonthValue >= 4) period.getYear else period.getYear - 1
    MonthlyReturnReportingPeriod(f"$startYear%04d-${(startYear + 1) % 100}%02d", period.getMonthValue)
  }
}
