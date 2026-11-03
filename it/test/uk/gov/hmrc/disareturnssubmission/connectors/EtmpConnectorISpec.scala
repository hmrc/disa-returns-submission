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

package uk.gov.hmrc.disareturnssubmission.connectors

import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, getRequestedFor, stubFor, urlEqualTo, verify}
import play.api.http.HeaderNames.AUTHORIZATION
import play.api.http.Status.{NOT_FOUND, OK, SERVICE_UNAVAILABLE}
import play.api.libs.json.Json
import uk.gov.hmrc.disareturnssubmission.BaseIntegrationSpec
import uk.gov.hmrc.disareturnssubmission.models.monthly.MonthlyReturnObligationStatus
import uk.gov.hmrc.http.{Authorization, HeaderCarrier, JsValidationException, UpstreamErrorResponse}

class EtmpConnectorISpec extends BaseIntegrationSpec {

  private val etmpObligationPath    = s"/etmp/check-obligation-status/$testZReference"
  private val callAmountWithRetries = 4

  private lazy val connector = inject[EtmpConnector]

  private implicit val hc: HeaderCarrier = HeaderCarrier(authorization = Some(Authorization(validInternalAuthToken)))

  private def stubEtmpObligationStatus(status: Int, body: String): Unit =
    stubFor(
      WireMock
        .get(urlEqualTo(etmpObligationPath))
        .willReturn(
          aResponse()
            .withStatus(status)
            .withHeader("Content-Type", "application/json")
            .withBody(body)
        )
    )

  private def obligationStatusBody(obligationAlreadyMet: Boolean): String =
    Json.obj("obligationAlreadyMet" -> obligationAlreadyMet).toString()

  "EtmpConnector.getMonthlyReturnObligationStatus" should {

    "return obligationAlreadyMet false when ETMP reports the obligation as open" in {
      stubEtmpObligationStatus(OK, obligationStatusBody(obligationAlreadyMet = false))

      connector.getMonthlyReturnObligationStatus(testZReference).futureValue shouldBe
        MonthlyReturnObligationStatus(obligationAlreadyMet = false)
    }

    "return obligationAlreadyMet true when ETMP reports the obligation as already met" in {
      stubEtmpObligationStatus(OK, obligationStatusBody(obligationAlreadyMet = true))

      connector.getMonthlyReturnObligationStatus(testZReference).futureValue shouldBe
        MonthlyReturnObligationStatus(obligationAlreadyMet = true)
    }

    "call ETMP once without forwarding the inbound authorization header" in {
      stubEtmpObligationStatus(OK, obligationStatusBody(obligationAlreadyMet = false))

      connector.getMonthlyReturnObligationStatus(testZReference).futureValue

      verify(1, getRequestedFor(urlEqualTo(etmpObligationPath)).withoutHeader(AUTHORIZATION))
    }

    "fail with an UpstreamErrorResponse after retrying when ETMP returns a 5xx" in {
      stubEtmpObligationStatus(SERVICE_UNAVAILABLE, "{}")

      val result = connector.getMonthlyReturnObligationStatus(testZReference).failed.futureValue

      result                                                shouldBe a[UpstreamErrorResponse]
      result.asInstanceOf[UpstreamErrorResponse].statusCode shouldBe SERVICE_UNAVAILABLE
      verify(callAmountWithRetries, getRequestedFor(urlEqualTo(etmpObligationPath)))
    }

    "fail with an UpstreamErrorResponse without retrying when ETMP returns a 4xx" in {
      stubEtmpObligationStatus(NOT_FOUND, "{}")

      val result = connector.getMonthlyReturnObligationStatus(testZReference).failed.futureValue

      result                                                shouldBe a[UpstreamErrorResponse]
      result.asInstanceOf[UpstreamErrorResponse].statusCode shouldBe NOT_FOUND
      verify(1, getRequestedFor(urlEqualTo(etmpObligationPath)))
    }

    "fail with a JsValidationException when ETMP returns an unexpected body" in {
      stubEtmpObligationStatus(OK, Json.obj("unexpected" -> "field").toString())

      connector.getMonthlyReturnObligationStatus(testZReference).failed.futureValue shouldBe a[JsValidationException]
    }
  }
}
