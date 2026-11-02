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

package uk.gov.hmrc.disareturnssubmission.service

import base.SpecBase
import org.mockito.ArgumentMatchers.{any, eq as eqTo}
import org.mockito.Mockito.{reset, when}
import org.scalatest.BeforeAndAfterEach
import uk.gov.hmrc.disareturnssubmission.connectors.EtmpConnector
import uk.gov.hmrc.disareturnssubmission.models.monthly.*
import uk.gov.hmrc.disareturnssubmission.repositories.MonthlyReturnRepository
import uk.gov.hmrc.disareturnssubmission.services.*
import uk.gov.hmrc.http.{HeaderCarrier, UpstreamErrorResponse}

import java.time.Instant
import scala.concurrent.Future

class MonthlyReturnReportingStatusServiceSpec extends SpecBase with BeforeAndAfterEach {

  private val mockReportingWindowService  = mock[MonthlyReturnReportingWindowService]
  private val mockEtmpConnector           = mock[EtmpConnector]
  private val mockMonthlyReturnRepository = mock[MonthlyReturnRepository]

  private val service =
    new MonthlyReturnReportingStatusService(mockReportingWindowService, mockEtmpConnector, mockMonthlyReturnRepository)

  private implicit val hc: HeaderCarrier = HeaderCarrier()

  private val testInstant     = Instant.parse("2026-06-12T00:00:00Z")
  private val testWindowStart = Instant.parse("2026-06-06T00:00:00Z")
  private val testWindowEnd   = Instant.parse("2026-06-19T23:59:59Z")
  private val testDeclaredOn  = Instant.parse("2026-06-10T09:00:00Z")

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    reset(mockReportingWindowService, mockEtmpConnector, mockMonthlyReturnRepository)
  }

  private def givenReportingWindow(isOpen: Boolean = true): Unit =
    when(mockReportingWindowService.resolve(testZReference)).thenReturn(
      Future.successful(ResolvedReportingWindow(testInstant, testWindowStart, testWindowEnd, isOpen))
    )

  private def givenObligationAlreadyMet(obligationAlreadyMet: Boolean): Unit =
    when(mockEtmpConnector.getMonthlyReturnObligationStatus(eqTo(testZReference))(any[HeaderCarrier]))
      .thenReturn(Future.successful(MonthlyReturnObligationStatus(obligationAlreadyMet)))

  private def givenMonthlyReturn(result: Future[Option[MonthlyReturn]]): Unit =
    when(mockMonthlyReturnRepository.get(testZReference, testTaxYear, testMonth)).thenReturn(result)

  private def expectedStatus(
    reportingWindowOpen: Boolean = true,
    obligationClosed: Boolean = false,
    monthlyReturnDeclared: Boolean = false
  ): MonthlyReturnReportingStatus =
    MonthlyReturnReportingStatus(
      reportingWindowOpen = reportingWindowOpen,
      reportingWindowStart = testWindowStart,
      reportingWindowEnd = testWindowEnd,
      resolvedAt = testInstant,
      taxYear = testTaxYear,
      month = testMonth,
      obligationClosed = obligationClosed,
      monthlyReturnDeclared = monthlyReturnDeclared
    )

  "MonthlyReturnReportingStatusService.get" - {

    "must return an open status when the window is open, the obligation is open and no monthly return exists" in {
      givenReportingWindow()
      givenObligationAlreadyMet(false)
      givenMonthlyReturn(Future.successful(None))

      service.get(testZReference).futureValue mustBe expectedStatus()
    }

    "must report the reporting window as closed" in {
      givenReportingWindow(isOpen = false)
      givenObligationAlreadyMet(false)
      givenMonthlyReturn(Future.successful(None))

      service.get(testZReference).futureValue mustBe expectedStatus(reportingWindowOpen = false)
    }

    "must report the obligation as closed when ETMP says the obligation is already met" in {
      givenReportingWindow()
      givenObligationAlreadyMet(true)
      givenMonthlyReturn(Future.successful(None))

      service.get(testZReference).futureValue mustBe expectedStatus(obligationClosed = true)
    }

    "must report an undeclared monthly return as not declared" in {
      givenReportingWindow()
      givenObligationAlreadyMet(false)
      givenMonthlyReturn(Future.successful(Some(monthlyReturn)))

      service.get(testZReference).futureValue mustBe expectedStatus()
    }

    "must report a declared monthly return as declared" in {
      givenReportingWindow()
      givenObligationAlreadyMet(false)
      givenMonthlyReturn(Future.successful(Some(monthlyReturn.copy(declaredOn = Some(testDeclaredOn)))))

      service.get(testZReference).futureValue mustBe expectedStatus(monthlyReturnDeclared = true)
    }

    "must report all blocking conditions together" in {
      givenReportingWindow(isOpen = false)
      givenObligationAlreadyMet(true)
      givenMonthlyReturn(Future.successful(Some(monthlyReturn.copy(declaredOn = Some(testDeclaredOn)))))

      service.get(testZReference).futureValue mustBe expectedStatus(
        reportingWindowOpen = false,
        obligationClosed = true,
        monthlyReturnDeclared = true
      )
    }

    "must fail when the ETMP call fails" in {
      val etmpFailure = UpstreamErrorResponse("ETMP down", 503)
      givenReportingWindow()
      when(mockEtmpConnector.getMonthlyReturnObligationStatus(eqTo(testZReference))(any[HeaderCarrier]))
        .thenReturn(Future.failed(etmpFailure))
      givenMonthlyReturn(Future.successful(None))

      service.get(testZReference).failed.futureValue mustBe etmpFailure
    }

    "must fail when the monthly return lookup fails" in {
      givenReportingWindow()
      givenObligationAlreadyMet(false)
      givenMonthlyReturn(Future.failed(new RuntimeException(testMongoDownMessage)))

      service.get(testZReference).failed.futureValue.getMessage mustBe testMongoDownMessage
    }
  }
}
