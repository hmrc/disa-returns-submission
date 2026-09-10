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
import org.mockito.Mockito.{reset, verify, verifyNoInteractions, when}
import org.scalatest.BeforeAndAfterEach
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers.*
import uk.gov.hmrc.disareturnssubmission.testOnly.models.*
import uk.gov.hmrc.disareturnssubmission.testOnly.services.TestOverrideService
import uk.gov.hmrc.disareturnssubmission.validators.StrictZReferenceValidator

import java.time.{Instant, LocalDate}
import scala.concurrent.Future

class TestOnlyOverridesControllerSpec extends SpecBase with BeforeAndAfterEach {

  private val service      = mock[TestOverrideService]
  private val controller   = new TestOnlyOverridesController(
    stubControllerComponents(),
    service,
    new StrictZReferenceValidator
  )
  private val instant      = Instant.parse("2026-05-17T00:00:00Z")
  private val testOverride = TestOverride(
    testZReference,
    Some(ClockOverride(LocalDate.parse("2026-05-17"))),
    Some(ReportingWindowOverride(instant.minusSeconds(60), instant.plusSeconds(60)))
  )

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    reset(service)
  }

  "TestOnlyOverridesController" - {

    "must retain single-reference GET and normalize its reference" in {
      when(service.get(testZReference)).thenReturn(Future.successful(testOverride))

      val result = controller.get(s"  ${testZReference.toLowerCase}  ")(FakeRequest(GET, "/overrides/Z1234"))

      status(result) mustBe OK
      contentAsJson(result) mustBe Json.toJson(testOverride)
    }

    "must normalize, deduplicate and fully replace overrides in bulk" in {
      val zReferences   = Seq(testZReference, "Z5678")
      val rawReferences = Seq(" z1234 ", testZReference, "z5678")
      val request       = TestOverrideRequest(rawReferences, testOverride.clock, testOverride.reportingWindow)
      when(service.replace(zReferences, request)).thenReturn(Future.unit)

      val result = controller.put()(
        jsonRequest(
          PUT,
          "/overrides",
          Json.obj(
            "zReferences"     -> rawReferences,
            "clock"           -> Json.obj("date" -> "2026-05-17"),
            "reportingWindow" -> Json.obj(
              "startDate" -> "2026-05-16T23:59:00Z",
              "endDate"   -> "2026-05-17T00:01:00Z"
            )
          )
        )
      )

      status(result) mustBe NO_CONTENT
      verify(service).replace(zReferences, request)
    }

    "must reject an invalid reporting window before writing" in {
      val result = controller.put()(
        jsonRequest(
          PUT,
          "/overrides",
          Json.obj(
            "zReferences"     -> Seq(testZReference),
            "reportingWindow" -> Json.obj(
              "startDate" -> "2026-05-17T00:01:00Z",
              "endDate"   -> "2026-05-16T23:59:00Z"
            )
          )
        )
      )

      status(result) mustBe BAD_REQUEST
      (contentAsJson(result) \ "message").as[String] mustBe "invalid override request"
      verifyNoInteractions(service)
    }

    "must reject any invalid Z-reference before writing" in {
      val result = controller.put()(
        jsonRequest(PUT, "/overrides", Json.obj("zReferences" -> Seq(testZReference, "invalid")))
      )

      status(result) mustBe BAD_REQUEST
      (contentAsJson(result) \ "message").as[String] mustBe
        "zReferences must be a non-empty array of valid Z-references"
      verifyNoInteractions(service)
    }

    "must bulk delete normalized and deduplicated references" in {
      val zReferences = Seq(testZReference, "Z5678")
      when(service.delete(zReferences)).thenReturn(Future.unit)

      val result = controller.delete()(
        jsonRequest(POST, "/overrides/delete", Json.obj("zReferences" -> Seq("z1234", testZReference, "Z5678")))
      )

      status(result) mustBe NO_CONTENT
      verify(service).delete(zReferences)
    }

    "must return 503 when a bulk mutation fails" in {
      when(service.delete(Seq(testZReference))).thenReturn(Future.failed(new RuntimeException("boom")))

      val result = controller.delete()(
        jsonRequest(POST, "/overrides/delete", Json.obj("zReferences" -> Seq(testZReference)))
      )

      status(result) mustBe SERVICE_UNAVAILABLE
    }
  }

  private def jsonRequest(method: String, path: String, body: play.api.libs.json.JsValue) =
    FakeRequest(method, path).withHeaders("Content-Type" -> "application/json").withBody(body)
}
