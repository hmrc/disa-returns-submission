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

import base.SpecBase
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.pekko.actor.ActorSystem
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.{any, eq as eqTo}
import org.mockito.Mockito.{times, verify, when}
import play.api.http.Status.{INTERNAL_SERVER_ERROR, NOT_FOUND}
import uk.gov.hmrc.disareturnssubmission.config.AppConfig
import uk.gov.hmrc.disareturnssubmission.models.monthly.MonthlyReturnObligationStatus
import uk.gov.hmrc.http.client.{HttpClientV2, RequestBuilder}
import uk.gov.hmrc.http.{Authorization, HeaderCarrier, StringContextOps, UpstreamErrorResponse}

import scala.concurrent.Future

class EtmpConnectorSpec extends SpecBase {

  private val etmpBaseUrl           = "http://localhost:1204"
  private val obligationStatusUrl   = url"$etmpBaseUrl/etmp/check-obligation-status/$testZReference"
  private val callAmountWithRetries = 4
  private val retryConfig: Config   =
    ConfigFactory.parseString("http-verbs.retries.intervals = [1 millisecond, 1 millisecond, 1 millisecond]")

  private implicit val hc: HeaderCarrier = HeaderCarrier(authorization = Some(Authorization("internal-auth-token")))

  trait TestSetup {
    val mockAppConfig: AppConfig           = mock[AppConfig]
    val mockHttpClient: HttpClientV2       = mock[HttpClientV2]
    val mockRequestBuilder: RequestBuilder = mock[RequestBuilder]

    lazy val connector = new EtmpConnector(mockHttpClient, mockAppConfig, retryConfig, inject[ActorSystem])

    when(mockAppConfig.etmpBaseUrl).thenReturn(etmpBaseUrl)
    when(mockHttpClient.get(eqTo(obligationStatusUrl))(any[HeaderCarrier])).thenReturn(mockRequestBuilder)

    def obligationStatusResponse(result: Future[MonthlyReturnObligationStatus]): Unit =
      when(mockRequestBuilder.execute[MonthlyReturnObligationStatus](any(), any())).thenReturn(result)
  }

  "EtmpConnector" - {

    "getMonthlyReturnObligationStatus" - {

      "must return the obligation status when the obligation is open" in new TestSetup {
        obligationStatusResponse(Future.successful(MonthlyReturnObligationStatus(obligationAlreadyMet = false)))

        connector.getMonthlyReturnObligationStatus(testZReference).futureValue mustBe
          MonthlyReturnObligationStatus(obligationAlreadyMet = false)
      }

      "must return the obligation status when the obligation is already met" in new TestSetup {
        obligationStatusResponse(Future.successful(MonthlyReturnObligationStatus(obligationAlreadyMet = true)))

        connector.getMonthlyReturnObligationStatus(testZReference).futureValue mustBe
          MonthlyReturnObligationStatus(obligationAlreadyMet = true)
      }

      "must not forward the inbound authorization header to ETMP" in new TestSetup {
        obligationStatusResponse(Future.successful(MonthlyReturnObligationStatus(obligationAlreadyMet = false)))
        val headerCarrierCaptor: ArgumentCaptor[HeaderCarrier] = ArgumentCaptor.forClass(classOf[HeaderCarrier])

        connector.getMonthlyReturnObligationStatus(testZReference).futureValue

        verify(mockHttpClient).get(eqTo(obligationStatusUrl))(headerCarrierCaptor.capture())
        headerCarrierCaptor.getValue.authorization mustBe None
      }

      "must retry when ETMP returns a 5xx error" in new TestSetup {
        val upstreamError = UpstreamErrorResponse("ETMP unavailable", INTERNAL_SERVER_ERROR)
        obligationStatusResponse(Future.failed(upstreamError))

        connector.getMonthlyReturnObligationStatus(testZReference).failed.futureValue mustBe upstreamError

        verify(mockRequestBuilder, times(callAmountWithRetries)).execute[MonthlyReturnObligationStatus](any(), any())
      }

      "must not retry when ETMP returns a 4xx error" in new TestSetup {
        val upstreamError = UpstreamErrorResponse("Not found", NOT_FOUND)
        obligationStatusResponse(Future.failed(upstreamError))

        connector.getMonthlyReturnObligationStatus(testZReference).failed.futureValue mustBe upstreamError

        verify(mockRequestBuilder, times(1)).execute[MonthlyReturnObligationStatus](any(), any())
      }

      "must not retry when the request fails with a non-upstream error" in new TestSetup {
        val exception = new RuntimeException("connection reset")
        obligationStatusResponse(Future.failed(exception))

        connector.getMonthlyReturnObligationStatus(testZReference).failed.futureValue mustBe exception

        verify(mockRequestBuilder, times(1)).execute[MonthlyReturnObligationStatus](any(), any())
      }
    }
  }
}
