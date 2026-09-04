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
import uk.gov.hmrc.disareturnssubmission.testOnly.models.*
import uk.gov.hmrc.disareturnssubmission.testOnly.repositories.TestOverrideRepository

import java.time.{Instant, LocalDate}
import scala.concurrent.Future

class TestOverrideServiceSpec extends SpecBase {

  private val repository    = mock[TestOverrideRepository]
  private val service       = new TestOverrideService(repository)
  private val systemInstant = Instant.parse("2026-06-17T12:00:00Z")

  "TestOverrideService" - {

    "must return the public model after replacement" in {
      val request   = TestOverrideRequest(
        Some(ClockOverride(LocalDate.parse("2026-06-20"))),
        Some(
          ReportingWindowOverride(
            Instant.parse("2026-06-19T23:59:00Z"),
            Instant.parse("2026-06-20T00:01:00Z")
          )
        )
      )
      val aggregate = TestOverrideDocument(
        testZReference,
        request.clock,
        request.reportingWindow,
        systemInstant.plusSeconds(3600),
        systemInstant
      )
      when(repository.replace(testZReference, request)).thenReturn(Future.successful(aggregate))

      val result = service.replace(testZReference, request).futureValue

      result mustBe TestOverride(testZReference, request.clock, request.reportingWindow)
    }

    "must return empty options when no active aggregate exists" in {
      when(repository.getActive(testZReference)).thenReturn(Future.successful(None))

      val result = service.get(testZReference).futureValue

      result mustBe TestOverride(testZReference, None, None)
    }

    "must delete the full aggregate and return empty options" in {
      when(repository.delete(testZReference)).thenReturn(Future.unit)

      service.delete(testZReference).futureValue mustBe TestOverride(testZReference, None, None)

      verify(repository).delete(testZReference)
    }
  }
}
