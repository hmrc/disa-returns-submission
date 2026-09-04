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

package uk.gov.hmrc.disareturnssubmission.testOnly.services

import base.SpecBase
import org.mockito.Mockito.{verify, when}
import uk.gov.hmrc.disareturnssubmission.config.AppConfig
import uk.gov.hmrc.disareturnssubmission.services.ResolvedInstant
import uk.gov.hmrc.disareturnssubmission.testOnly.OverrideTimeSource
import uk.gov.hmrc.disareturnssubmission.testOnly.models.{ClockOverride, ReportingWindowOverride, TestOverrideDocument}
import uk.gov.hmrc.disareturnssubmission.testOnly.repositories.TestOverrideRepository

import java.time.{Instant, LocalDate}
import scala.concurrent.Future

class OverrideReportingWindowServiceSpec extends SpecBase {

  private val now        = Instant.parse("2026-04-01T12:00:00Z")
  private val timeSource = mock[OverrideTimeSource]
  private val repository = mock[TestOverrideRepository]
  private val service    = new OverrideReportingWindowService(inject[AppConfig], timeSource, repository)

  "OverrideReportingWindowService" - {

    "must evaluate clock and window from one aggregate snapshot" in {
      val supplied  = now.plusSeconds(120)
      val aggregate = Some(
        TestOverrideDocument(
          testZReference,
          Some(ClockOverride(LocalDate.parse("2026-04-01"))),
          Some(ReportingWindowOverride(now.plusSeconds(60), now.plusSeconds(180))),
          now.plusSeconds(3600),
          now
        )
      )
      when(repository.getActive(testZReference)).thenReturn(Future.successful(aggregate))
      when(timeSource.resolve(testZReference, aggregate)).thenReturn(
        Future.successful(ResolvedInstant(supplied, overridden = true))
      )

      val result = service.resolve(testZReference).futureValue

      result.instant mustBe supplied
      result.isOpen mustBe true
      verify(repository).getActive(testZReference)
      verify(timeSource).resolve(testZReference, aggregate)
    }

    "must use the default window when the aggregate window is absent" in {
      val aggregate = Some(
        TestOverrideDocument(
          testZReference,
          None,
          None,
          now.plusSeconds(3600),
          now
        )
      )
      when(repository.getActive(testZReference)).thenReturn(Future.successful(aggregate))
      when(timeSource.resolve(testZReference, aggregate)).thenReturn(
        Future.successful(ResolvedInstant(now, overridden = false))
      )

      service.resolve(testZReference).futureValue.isOpen mustBe false
    }

    "must apply the aggregate window to a supplied instant" in {
      val aggregate = Some(
        TestOverrideDocument(
          testZReference,
          None,
          Some(ReportingWindowOverride(now.minusSeconds(60), now.plusSeconds(60))),
          now.plusSeconds(3600),
          now
        )
      )
      when(repository.getActive(testZReference)).thenReturn(Future.successful(aggregate))

      service.isOpenAt(testZReference, now).futureValue mustBe true
    }
  }
}
