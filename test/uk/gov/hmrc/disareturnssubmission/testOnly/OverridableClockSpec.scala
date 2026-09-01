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

package uk.gov.hmrc.disareturnssubmission.testOnly

import base.SpecBase
import org.mockito.Mockito.{verify, when}
import uk.gov.hmrc.disareturnssubmission.services.SystemClock
import uk.gov.hmrc.disareturnssubmission.testOnly.models.ClockOverride
import uk.gov.hmrc.disareturnssubmission.testOnly.repositories.ClockOverrideRepository

import java.time.{Instant, LocalDate, ZoneOffset}
import scala.concurrent.Future

class OverridableClockSpec extends SpecBase {

  private val repository  = mock[ClockOverrideRepository]
  private val systemClock = mock[SystemClock]
  private val clock       = new OverridableClock(systemClock, repository)
  private val now         = Instant.parse("2026-09-17T00:00:00Z")

  "OverridableClock" - {

    "must use a persisted override" in {
      when(repository.getActive(testZReference)).thenReturn(
        Future.successful(Some(ClockOverride(testZReference, now, now.plusSeconds(3600), now)))
      )

      clock.instant(testZReference).futureValue mustBe now
    }

    "must fall back to the system clock" in {
      when(repository.getActive(testZReference)).thenReturn(Future.successful(None))
      when(systemClock.instant(testZReference)).thenReturn(Future.successful(now))

      clock.instant(testZReference).futureValue mustBe now
    }

    "must persist and delete an override for the Z-reference" in {
      val date    = LocalDate.parse("2026-09-17")
      val instant = date.atStartOfDay(ZoneOffset.UTC).toInstant
      when(repository.set(testZReference, instant)).thenReturn(Future.unit)
      when(repository.delete(testZReference)).thenReturn(Future.unit)

      clock.setDate(testZReference, date).futureValue
      clock.reset(testZReference).futureValue

      verify(repository).set(testZReference, instant)
      verify(repository).delete(testZReference)
    }
  }
}
