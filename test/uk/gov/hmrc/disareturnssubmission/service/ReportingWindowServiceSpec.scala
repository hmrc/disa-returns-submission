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

package uk.gov.hmrc.disareturnssubmission.service

import base.SpecBase
import org.mockito.Mockito.{verify, when}
import uk.gov.hmrc.disareturnssubmission.config.AppConfig
import uk.gov.hmrc.disareturnssubmission.services.{ReportingWindowService, ResolvedInstant, TimeSource}

import java.time.Instant
import scala.concurrent.Future

class ReportingWindowServiceSpec extends SpecBase {

  private val appConfig = inject[AppConfig]

  private def buildService(now: Instant): ReportingWindowService =
    new ReportingWindowService(
      appConfig = appConfig,
      timeSource = (_: String) => Future.successful(now)
    )

  "ReportingWindowService" - {

    "isOpen" - {

      "must resolve time once and evaluate that exact instant" in {
        val timeSource = mock[TimeSource]
        val instant    = Instant.parse("2026-04-12T00:00:00Z")
        when(timeSource.resolve(testZReference)).thenReturn(
          Future.successful(ResolvedInstant(instant, overridden = true))
        )

        new ReportingWindowService(appConfig, timeSource).isOpen(testZReference).futureValue mustBe true

        verify(timeSource).resolve(testZReference)
      }

      "must return true when today's day of month is the declarationPeriodStart" in {
        buildService(Instant.parse(s"2026-04-0${appConfig.declarationPeriodStart}T00:00:00Z"))
          .isOpen(testZReference)
          .futureValue mustBe true
      }

      "must return true when today's day of month is the declarationPeriodEnd" in {
        buildService(Instant.parse(s"2026-04-${appConfig.declarationPeriodEnd}T00:00:00Z"))
          .isOpen(testZReference)
          .futureValue mustBe true
      }

      "must return true when today's day of month is between declarationPeriodStart and declarationPeriodEnd" in {
        buildService(Instant.parse("2026-04-12T00:00:00Z")).isOpen(testZReference).futureValue mustBe true
      }

      "must return false when today's day of month is before declarationPeriodStart" in {
        buildService(Instant.parse("2026-04-01T00:00:00Z")).isOpen(testZReference).futureValue mustBe false
      }

      "must return false when today's day of month is after declarationPeriodEnd" in {
        buildService(Instant.parse("2026-04-25T00:00:00Z")).isOpen(testZReference).futureValue mustBe false
      }
    }

    "isOpenAt must evaluate the supplied instant" in {
      buildService(Instant.parse("2026-04-01T00:00:00Z"))
        .isOpenAt(testZReference, Instant.parse("2026-04-12T00:00:00Z"))
        .futureValue mustBe true
    }
  }
}
