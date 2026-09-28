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

import java.time.{Instant, LocalDate, ZoneOffset}
import scala.concurrent.Future

class ReportingWindowServiceSpec extends SpecBase {

  private val appConfig = inject[AppConfig]

  private def buildService(now: Instant, config: AppConfig = appConfig): ReportingWindowService =
    new ReportingWindowService(
      appConfig = config,
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

    "resolve must return the default window bounds for the instant's calendar month" in {
      val instant       = Instant.parse("2026-04-12T00:00:00Z")
      val expectedStart =
        LocalDate.of(2026, 4, appConfig.declarationPeriodStart).atStartOfDay(ZoneOffset.UTC).toInstant
      val expectedEnd   =
        LocalDate.of(2026, 4, appConfig.declarationPeriodEnd).atTime(23, 59, 59).atZone(ZoneOffset.UTC).toInstant

      val result = buildService(instant).resolve(testZReference).futureValue

      result.instant mustBe instant
      result.windowStart mustBe expectedStart
      result.windowEnd mustBe expectedEnd
      result.isOpen mustBe true
    }

    "resolve must cap a configured end day of 31 at the last day of shorter months" in {
      val config = mock[AppConfig]
      when(config.declarationPeriodStart).thenReturn(6)
      when(config.declarationPeriodEnd).thenReturn(31)

      Seq(
        ("2026-09-25", "2026-09-30"),
        ("2026-02-25", "2026-02-28"),
        ("2028-02-25", "2028-02-29"),
        ("2026-12-25", "2026-12-31")
      ).foreach { (today, lastDay) =>
        val result = buildService(Instant.parse(s"${today}T12:00:00Z"), config).resolve(testZReference).futureValue

        result.windowStart mustBe LocalDate.parse(today).withDayOfMonth(6).atStartOfDay(ZoneOffset.UTC).toInstant
        result.windowEnd mustBe LocalDate.parse(lastDay).atTime(23, 59, 59).atZone(ZoneOffset.UTC).toInstant
        result.isOpen mustBe true
      }
    }

    "resolve must cap a configured start and end day of 31 and open only on the last day" in {
      val config = mock[AppConfig]
      when(config.declarationPeriodStart).thenReturn(31)
      when(config.declarationPeriodEnd).thenReturn(31)

      Seq(
        ("2026-09-30", "2026-09-29"),
        ("2026-02-28", "2026-02-27"),
        ("2028-02-29", "2028-02-28"),
        ("2026-12-31", "2026-12-30")
      ).foreach { (lastDay, previousDay) =>
        val lastDate = LocalDate.parse(lastDay)
        val result   = buildService(Instant.parse(s"${lastDay}T12:00:00Z"), config).resolve(testZReference).futureValue

        result.windowStart mustBe lastDate.atStartOfDay(ZoneOffset.UTC).toInstant
        result.windowEnd mustBe lastDate.atTime(23, 59, 59).atZone(ZoneOffset.UTC).toInstant
        result.isOpen mustBe true
        buildService(Instant.parse(s"${previousDay}T12:00:00Z"), config).isOpen(testZReference).futureValue mustBe false
      }
    }
  }
}
