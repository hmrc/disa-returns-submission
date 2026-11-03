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

import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, getRequestedFor, stubFor, urlEqualTo, verify}
import play.api.http.HeaderNames.AUTHORIZATION
import play.api.http.Status.{BAD_REQUEST, FORBIDDEN, INTERNAL_SERVER_ERROR, NOT_FOUND, OK, SERVICE_UNAVAILABLE, UNAUTHORIZED}
import play.api.libs.json.Json
import play.api.test.Helpers.await
import uk.gov.hmrc.disareturnssubmission.BaseIntegrationSpec
import uk.gov.hmrc.disareturnssubmission.repositories.MonthlyReturnRepository

class MonthlyReturnReportingStatusControllerISpec extends BaseIntegrationSpec {

  private val reportingStatusPath = s"$testServicePath/monthly/$testZReference/reporting-status"
  private val etmpObligationPath  = s"/etmp/check-obligation-status/$testZReference"

  private lazy val repository = inject[MonthlyReturnRepository]

  private def stubEtmpObligationStatus(status: Int = OK, obligationAlreadyMet: Boolean = false): Unit =
    stubFor(
      WireMock
        .get(urlEqualTo(etmpObligationPath))
        .willReturn(
          aResponse()
            .withStatus(status)
            .withHeader("Content-Type", "application/json")
            .withBody(Json.obj("obligationAlreadyMet" -> obligationAlreadyMet).toString())
        )
    )

  "GET /monthly/:zReference/reporting-status" should {

    "return 200 OK with the window, period, obligation and declaration status when nothing has been declared" in {
      stubEtmpObligationStatus()

      val result = get(reportingStatusPath)

      result.status                                       shouldBe OK
      (result.json \ "reportingWindowOpen").as[Boolean]   shouldBe true
      (result.json \ "taxYear").as[String]                shouldBe testTaxYear
      (result.json \ "month").as[Int]                     shouldBe testMonth
      (result.json \ "obligationClosed").as[Boolean]      shouldBe false
      (result.json \ "monthlyReturnDeclared").as[Boolean] shouldBe false
    }

    "return obligationClosed true when ETMP reports the obligation as already met" in {
      stubEtmpObligationStatus(obligationAlreadyMet = true)

      val result = get(reportingStatusPath)

      result.status                                  shouldBe OK
      (result.json \ "obligationClosed").as[Boolean] shouldBe true
    }

    "return monthlyReturnDeclared true when the monthly return for the reporting period has been declared" in {
      stubEtmpObligationStatus()
      await(repository.create(testZReference, testTaxYear, testMonth, testSubmissionId, nilReturn = true))
      await(repository.declare(testZReference, testTaxYear, testMonth))

      val result = get(reportingStatusPath)

      result.status                                       shouldBe OK
      (result.json \ "monthlyReturnDeclared").as[Boolean] shouldBe true
    }

    "not forward the internal auth token to ETMP" in {
      stubEtmpObligationStatus()

      get(reportingStatusPath).status shouldBe OK

      verify(getRequestedFor(urlEqualTo(etmpObligationPath)).withoutHeader(AUTHORIZATION))
    }

    "return 500 Internal Server Error after retrying when ETMP returns a 5xx" in {
      stubEtmpObligationStatus(status = SERVICE_UNAVAILABLE)

      val result = get(reportingStatusPath)

      result.status shouldBe INTERNAL_SERVER_ERROR
      verify(4, getRequestedFor(urlEqualTo(etmpObligationPath)))
    }

    "return 500 Internal Server Error without retrying when ETMP returns a 4xx" in {
      stubEtmpObligationStatus(status = NOT_FOUND)

      val result = get(reportingStatusPath)

      result.status shouldBe INTERNAL_SERVER_ERROR
      verify(1, getRequestedFor(urlEqualTo(etmpObligationPath)))
    }

    "return 400 Bad Request when the zReference is invalid" in {
      val result = get(s"$testServicePath/monthly/$invalidTestZReference/reporting-status")

      result.status shouldBe BAD_REQUEST
    }

    "return 401 Unauthorized when no internal auth token is provided" in {
      getWithoutAuthorization(reportingStatusPath).status shouldBe UNAUTHORIZED
    }

    "return 401 Unauthorized when the internal auth token is invalid" in {
      getWithAuthorization(reportingStatusPath, invalidInternalAuthToken).status shouldBe UNAUTHORIZED
    }

    "return 403 Forbidden when the internal auth token does not have permission" in {
      getWithAuthorization(reportingStatusPath, forbiddenInternalAuthToken).status shouldBe FORBIDDEN
    }
  }
}
