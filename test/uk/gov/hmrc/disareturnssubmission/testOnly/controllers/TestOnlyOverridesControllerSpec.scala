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

package uk.gov.hmrc.disareturnssubmission.testOnly.controllers

import base.SpecBase
import org.mockito.ArgumentMatchers.eq as eqTo
import org.mockito.Mockito.{verify, when}
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers.*
import uk.gov.hmrc.disareturnssubmission.testOnly.models.*
import uk.gov.hmrc.disareturnssubmission.testOnly.services.TestOverrideService

import java.time.{Instant, LocalDate}
import scala.concurrent.Future

class TestOnlyOverridesControllerSpec extends SpecBase {

  private val service      = mock[TestOverrideService]
  private val controller   = new TestOnlyOverridesController(stubControllerComponents(), service)
  private val instant      = Instant.parse("2026-05-17T00:00:00Z")
  private val testOverride = TestOverride(
    testZReference,
    Some(ClockOverride(LocalDate.parse("2026-05-17"))),
    Some(ReportingWindowOverride(instant.minusSeconds(60), instant.plusSeconds(60)))
  )

  "TestOnlyOverridesController" - {

    "must return the public override" in {
      when(service.get(testZReference)).thenReturn(Future.successful(testOverride))

      val result = controller.get(testZReference.toLowerCase)(FakeRequest(GET, s"/overrides/$testZReference"))

      status(result) mustBe OK
      contentAsJson(result) mustBe Json.toJson(testOverride)
    }

    "must fully replace both optional override fields" in {
      val request = TestOverrideRequest(testOverride.clock, testOverride.reportingWindow)
      when(service.replace(eqTo(testZReference), eqTo(request))).thenReturn(Future.successful(testOverride))

      val result = controller.put(testZReference)(
        FakeRequest(PUT, s"/overrides/$testZReference")
          .withHeaders("Content-Type" -> "application/json")
          .withBody(
            Json.obj(
              "clock"           -> Json.obj("date" -> "2026-05-17"),
              "reportingWindow" -> Json.obj(
                "startDate" -> "2026-05-16T23:59:00Z",
                "endDate"   -> "2026-05-17T00:01:00Z"
              )
            )
          )
      )

      status(result) mustBe OK
      verify(service).replace(testZReference, request)
    }

    "must treat omitted fields as cleared" in {
      val request = TestOverrideRequest(None, None)
      when(service.replace(testZReference, request))
        .thenReturn(Future.successful(testOverride.copy(clock = None, reportingWindow = None)))

      val result = controller.put(testZReference)(
        FakeRequest(PUT, s"/overrides/$testZReference")
          .withHeaders("Content-Type" -> "application/json")
          .withBody(Json.obj())
      )

      status(result) mustBe OK
      verify(service).replace(testZReference, request)
    }

    "must reject an invalid reporting window" in {
      val result = controller.put(testZReference)(
        FakeRequest(PUT, s"/overrides/$testZReference")
          .withHeaders("Content-Type" -> "application/json")
          .withBody(
            Json.obj(
              "reportingWindow" -> Json.obj(
                "startDate" -> "2026-05-17T00:01:00Z",
                "endDate"   -> "2026-05-16T23:59:00Z"
              )
            )
          )
      )

      status(result) mustBe BAD_REQUEST
    }

    "must delete the entire aggregate and return empty options" in {
      val cleared = testOverride.copy(clock = None, reportingWindow = None)
      when(service.delete(testZReference)).thenReturn(Future.successful(cleared))

      val result = controller.delete(testZReference)(FakeRequest(DELETE, s"/overrides/$testZReference"))

      status(result) mustBe OK
      contentAsJson(result) mustBe Json.toJson(cleared)
    }
  }
}
