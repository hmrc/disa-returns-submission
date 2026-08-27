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
import play.api.http.HeaderNames.CONTENT_TYPE
import play.api.http.MimeTypes.JSON
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers.*
import uk.gov.hmrc.disareturnssubmission.testOnly.models.ReportingWindowOverrideRequest
import uk.gov.hmrc.disareturnssubmission.testOnly.services.MutableReportingWindowService

import java.time.Instant
import scala.concurrent.Future

class TestOnlyReportingWindowOverrideControllerSpec extends SpecBase {

  private val service    = mock[MutableReportingWindowService]
  private val controller = new TestOnlyReportingWindowOverrideController(stubControllerComponents(), service)
  private val startDate  = Instant.parse("2026-08-25T11:59:00Z")
  private val endDate    = Instant.parse("2026-08-25T12:01:00Z")

  "TestOnlyReportingWindowOverrideController" - {

    "must delete overrides for normalized Z-references" in {
      reset(service)
      val otherZReference = "Z5678"
      when(service.delete(Seq(testZReference, otherZReference))).thenReturn(Future.successful(()))

      val result = controller.delete()(
        FakeRequest(DELETE, "/test-only/reporting-window-override")
          .withHeaders(CONTENT_TYPE -> JSON)
          .withBody(Json.obj("zReferences" -> Seq(testZReference.toLowerCase, otherZReference, testZReference)))
      )

      status(result) mustBe NO_CONTENT
      verify(service).delete(Seq(testZReference, otherZReference))
    }

    "must reject a delete request containing an invalid Z-reference" in {
      reset(service)
      val result = controller.delete()(
        FakeRequest(DELETE, "/test-only/reporting-window-override")
          .withHeaders(CONTENT_TYPE -> JSON)
          .withBody(Json.obj("zReferences" -> Seq(testZReference, "invalid")))
      )

      status(result) mustBe BAD_REQUEST
      verifyNoInteractions(service)
    }

    "must reject an empty sequence of Z-references" in {
      reset(service)
      val result = controller.delete()(
        FakeRequest(DELETE, "/test-only/reporting-window-override")
          .withHeaders(CONTENT_TYPE -> JSON)
          .withBody(Json.obj("zReferences" -> Seq.empty[String]))
      )

      status(result) mustBe BAD_REQUEST
      verifyNoInteractions(service)
    }

    "must normalize and store a valid override" in {
      val overrideRequest = ReportingWindowOverrideRequest(startDate, endDate)
      when(service.set(testZReference, overrideRequest)).thenReturn(Future.successful(()))

      val result = controller.set(testZReference.toLowerCase)(
        FakeRequest(PUT, s"/test-only/reporting-window-override/${testZReference.toLowerCase}")
          .withHeaders(CONTENT_TYPE -> JSON)
          .withBody(Json.obj("startDate" -> startDate.toString, "endDate" -> endDate.toString))
      )

      status(result) mustBe NO_CONTENT
      verify(service).set(testZReference, overrideRequest)
    }

    "must reject an invalid interval" in {
      val result = controller.set(testZReference)(
        FakeRequest(PUT, s"/test-only/reporting-window-override/$testZReference")
          .withHeaders(CONTENT_TYPE -> JSON)
          .withBody(Json.obj("startDate" -> endDate.toString, "endDate" -> startDate.toString))
      )

      status(result) mustBe BAD_REQUEST
    }

    "must reject an invalid Z-reference" in {
      val result = controller.set("invalid")(
        FakeRequest(PUT, "/test-only/reporting-window-override/invalid")
          .withHeaders(CONTENT_TYPE -> JSON)
          .withBody(Json.obj("startDate" -> startDate.toString, "endDate" -> endDate.toString))
      )

      status(result) mustBe BAD_REQUEST
    }
  }
}
