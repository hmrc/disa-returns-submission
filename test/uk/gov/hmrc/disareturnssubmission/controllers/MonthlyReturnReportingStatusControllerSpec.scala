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
import org.mockito.ArgumentMatchers.{any, eq as eqTo}
import org.mockito.Mockito.{reset, when}
import org.scalatest.BeforeAndAfterEach
import play.api.Application
import play.api.http.HeaderNames.AUTHORIZATION
import play.api.inject.bind
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers.*
import uk.gov.hmrc.disareturnssubmission.models.monthly.MonthlyReturnReportingStatus
import uk.gov.hmrc.disareturnssubmission.services.MonthlyReturnReportingStatusService
import uk.gov.hmrc.http.{HeaderCarrier, UpstreamErrorResponse}

import java.time.Instant
import scala.concurrent.Future

class MonthlyReturnReportingStatusControllerSpec extends SpecBase with BeforeAndAfterEach {

  private val mockReportingStatusService = mock[MonthlyReturnReportingStatusService]

  override lazy val app: Application = applicationBuilder(
    Seq(
      bind[MonthlyReturnReportingStatusService].toInstance(mockReportingStatusService)
    )
  ).build()

  private lazy val controller = inject[MonthlyReturnReportingStatusController]

  private val path                   = s"/monthly/$testZReference/reporting-status"
  private val validInternalAuthToken = "valid-internal-auth-token-disa-returns-backend"

  private def authorizedRequest = FakeRequest(GET, path).withHeaders(AUTHORIZATION -> validInternalAuthToken)

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    reset(mockReportingStatusService)
  }

  private val testReportingStatus = MonthlyReturnReportingStatus(
    reportingWindowOpen = true,
    reportingWindowStart = Instant.parse("2026-06-06T00:00:00Z"),
    reportingWindowEnd = Instant.parse("2026-06-19T23:59:59Z"),
    resolvedAt = Instant.parse("2026-06-12T00:00:00Z"),
    taxYear = testTaxYear,
    month = testMonth,
    obligationClosed = false,
    monthlyReturnDeclared = true
  )

  private def givenStatus(result: Future[MonthlyReturnReportingStatus]): Unit =
    when(mockReportingStatusService.get(eqTo(testZReference))(any[HeaderCarrier])).thenReturn(result)

  "MonthlyReturnReportingStatusController" - {

    "status" - {

      "must return OK with the monthly return reporting status" in {
        givenStatus(Future.successful(testReportingStatus))

        val result = controller.status(testZReference)(authorizedRequest)

        status(result) mustBe OK
        contentAsJson(result) mustBe Json.toJson(testReportingStatus)
      }

      "must normalize the Z-reference" in {
        givenStatus(Future.successful(testReportingStatus))

        val result = controller.status(lowercaseTestZReference)(authorizedRequest)

        status(result) mustBe OK
      }

      "must reject an invalid Z-reference" in {
        val result = controller.status("invalid")(authorizedRequest)

        status(result) mustBe BAD_REQUEST
      }

      "must return INTERNAL_SERVER_ERROR when the ETMP call fails" in {
        givenStatus(Future.failed(UpstreamErrorResponse("ETMP down", 503)))

        val result = controller.status(testZReference)(authorizedRequest)

        status(result) mustBe INTERNAL_SERVER_ERROR
      }

      "must return INTERNAL_SERVER_ERROR when the monthly return lookup fails" in {
        givenStatus(Future.failed(new RuntimeException(testMongoDownMessage)))

        val result = controller.status(testZReference)(authorizedRequest)

        status(result) mustBe INTERNAL_SERVER_ERROR
      }
    }
  }
}
