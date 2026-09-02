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
import uk.gov.hmrc.disareturnssubmission.services.{ResolvedInstant, SystemClock}
import uk.gov.hmrc.disareturnssubmission.testOnly.models.{ClockOverride, TestOverrideDocument}
import uk.gov.hmrc.disareturnssubmission.testOnly.repositories.TestOverrideRepository

import java.time.{Instant, LocalDate}
import scala.concurrent.Future

class OverrideTimeSourceSpec extends SpecBase {

  private val repository  = mock[TestOverrideRepository]
  private val systemClock = mock[SystemClock]
  private val timeSource  = new OverrideTimeSource(systemClock, repository)
  private val now         = Instant.parse("2026-09-17T12:00:00Z")

  "OverrideTimeSource" - {

    "must resolve an aggregate clock from one repository lookup" in {
      when(repository.getActive(testZReference)).thenReturn(
        Future.successful(
          Some(TestOverrideDocument(testZReference, Some(ClockOverride(LocalDate.parse("2026-09-17"))), None, now, now))
        )
      )

      timeSource.resolve(testZReference).futureValue mustBe
        ResolvedInstant(Instant.parse("2026-09-17T00:00:00Z"), overridden = true)
      verify(repository).getActive(testZReference)
    }

    "must use SystemClock when the aggregate clock is absent" in {
      when(repository.getActive(testZReference)).thenReturn(
        Future.successful(Some(TestOverrideDocument(testZReference, None, None, now, now)))
      )
      when(systemClock.resolve(testZReference)).thenReturn(Future.successful(ResolvedInstant(now, overridden = false)))

      timeSource.resolve(testZReference).futureValue mustBe ResolvedInstant(now, overridden = false)
    }
  }
}
