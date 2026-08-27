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

package uk.gov.hmrc.disareturnssubmission.controllers

import base.SpecBase
import org.mockito.Mockito.{reset, when}
import org.scalatest.BeforeAndAfterEach
import play.api.Application
import play.api.http.HeaderNames.AUTHORIZATION
import play.api.inject.bind
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers.*
import uk.gov.hmrc.disareturnssubmission.models.ReportingWindowStatus
import uk.gov.hmrc.disareturnssubmission.services.ReportingWindowService

import scala.concurrent.Future

class ReportingWindowControllerSpec extends SpecBase with BeforeAndAfterEach {

  private val mockReportingWindowService = mock[ReportingWindowService]

  override lazy val app: Application = applicationBuilder(
    Seq(
      bind[ReportingWindowService].toInstance(mockReportingWindowService)
    )
  ).build()

  private lazy val controller = inject[ReportingWindowController]

  private val path                   = s"/reporting-window/status/$testZReference"
  private val validInternalAuthToken = "valid-internal-auth-token-disa-returns-backend"

  private def authorizedRequest(method: String, path: String) =
    FakeRequest(method, path).withHeaders(AUTHORIZATION -> validInternalAuthToken)

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    reset(mockReportingWindowService)
  }

  "ReportingWindowController" - {

    "status" - {

      "must return OK with reportingWindowOpen true when the reporting window is open" in {
        when(mockReportingWindowService.isOpen(testZReference)).thenReturn(Future.successful(true))

        val result = controller.status(testZReference)(authorizedRequest("GET", path))

        status(result) mustBe OK
        contentAsJson(result) mustBe Json.toJson(ReportingWindowStatus(reportingWindowOpen = true))
      }

      "must return OK with reportingWindowOpen false when the reporting window is closed" in {
        when(mockReportingWindowService.isOpen(testZReference)).thenReturn(Future.successful(false))

        val result = controller.status(testZReference)(authorizedRequest("GET", path))

        status(result) mustBe OK
        contentAsJson(result) mustBe Json.toJson(ReportingWindowStatus(reportingWindowOpen = false))
      }

      "must normalize the Z-reference" in {
        when(mockReportingWindowService.isOpen(testZReference)).thenReturn(Future.successful(true))

        val result = controller.status(testZReference.toLowerCase)(authorizedRequest("GET", path))

        status(result) mustBe OK
      }

      "must reject an invalid Z-reference" in {
        val result = controller.status("invalid")(authorizedRequest("GET", path))

        status(result) mustBe BAD_REQUEST
      }
    }
  }
}
