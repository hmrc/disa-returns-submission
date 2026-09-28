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

import java.time.{Instant, LocalDate, ZoneOffset}
import scala.concurrent.Future

class OverrideReportingWindowServiceSpec extends SpecBase {

  private val now        = Instant.parse("2026-04-01T12:00:00Z")
  private val timeSource = mock[OverrideTimeSource]
  private val repository = mock[TestOverrideRepository]
  private val appConfig  = inject[AppConfig]
  private val service    = new OverrideReportingWindowService(appConfig, timeSource, repository)

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
      result.windowStart mustBe now.plusSeconds(60)
      result.windowEnd mustBe now.plusSeconds(180)
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

      val result = service.resolve(testZReference).futureValue

      result.isOpen mustBe false
      result.windowStart mustBe
        LocalDate.of(2026, 4, appConfig.declarationPeriodStart).atStartOfDay(ZoneOffset.UTC).toInstant
      result.windowEnd mustBe
        LocalDate.of(2026, 4, appConfig.declarationPeriodEnd).atTime(23, 59, 59).atZone(ZoneOffset.UTC).toInstant
    }

    "must cap the default window when only the clock is overridden into a short month" in {
      val config    = mock[AppConfig]
      val september = Instant.parse("2026-09-25T12:00:00Z")
      val aggregate = Some(
        TestOverrideDocument(
          testZReference,
          Some(ClockOverride(LocalDate.parse("2026-09-25"))),
          None,
          now.plusSeconds(3600),
          now
        )
      )
      when(config.declarationPeriodStart).thenReturn(6)
      when(config.declarationPeriodEnd).thenReturn(31)
      when(repository.getActive(testZReference)).thenReturn(Future.successful(aggregate))
      when(timeSource.resolve(testZReference, aggregate)).thenReturn(
        Future.successful(ResolvedInstant(september, overridden = true))
      )

      val result =
        new OverrideReportingWindowService(config, timeSource, repository).resolve(testZReference).futureValue

      result.windowEnd mustBe Instant.parse("2026-09-30T23:59:59Z")
      result.isOpen mustBe true
    }
  }
}
