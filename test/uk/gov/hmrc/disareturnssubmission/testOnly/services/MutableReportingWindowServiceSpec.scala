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
import uk.gov.hmrc.disareturnssubmission.testOnly.models.{ReportingWindowOverride, ReportingWindowOverrideRequest}
import uk.gov.hmrc.disareturnssubmission.testOnly.repositories.ReportingWindowOverrideRepository

import java.time.{Clock, Instant, ZoneOffset}
import scala.concurrent.Future

class MutableReportingWindowServiceSpec extends SpecBase {

  private val now        = Instant.parse("2026-04-01T12:00:00Z")
  private val clock      = Clock.fixed(now, ZoneOffset.UTC)
  private val appConfig  = inject[AppConfig]
  private val repository = mock[ReportingWindowOverrideRepository]
  private val service    = new MutableReportingWindowService(appConfig, clock, repository)

  "MutableReportingWindowService" - {

    "must apply an active override for the Z-reference" in {
      when(repository.getActive(testZReference)).thenReturn(
        Future.successful(
          Some(
            ReportingWindowOverride(
              _id = testZReference,
              startDate = now.minusSeconds(60),
              endDate = now.plusSeconds(60),
              expiresAt = now.plusSeconds(3600),
              updatedAt = now
            )
          )
        )
      )

      service.isOpen(testZReference).futureValue mustBe true
    }

    "must use the default reporting window when no override exists" in {
      when(repository.getActive(testZReference)).thenReturn(Future.successful(None))

      service.isOpen(testZReference).futureValue mustBe false
    }

    "must store an override against the Z-reference" in {
      val request = ReportingWindowOverrideRequest(now.minusSeconds(60), now.plusSeconds(60))
      when(repository.set(testZReference, request)).thenReturn(Future.successful(()))

      service.set(testZReference, request).futureValue

      verify(repository).set(testZReference, request)
    }
  }
}
