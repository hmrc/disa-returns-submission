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

package uk.gov.hmrc.disareturnssubmission.services

import uk.gov.hmrc.disareturnssubmission.connectors.EtmpConnector
import uk.gov.hmrc.disareturnssubmission.models.monthly.*
import uk.gov.hmrc.disareturnssubmission.repositories.MonthlyReturnRepository
import uk.gov.hmrc.http.HeaderCarrier

import javax.inject.*
import scala.concurrent.*

@Singleton
class MonthlyReturnReportingStatusService @Inject() (
  reportingWindowService: MonthlyReturnReportingWindowService,
  etmpConnector: EtmpConnector,
  monthlyReturnRepository: MonthlyReturnRepository
)(implicit ec: ExecutionContext) {

  def get(zReference: String)(implicit hc: HeaderCarrier): Future[MonthlyReturnReportingStatus] =
    reportingWindowService.resolve(zReference).flatMap { reportingWindow =>
      val reportingPeriod = MonthlyReturnReportingPeriod.previousMonthOf(reportingWindow.instant)
      for {
        obligationStatus <- etmpConnector.getMonthlyReturnObligationStatus(zReference)
        monthlyReturn    <- monthlyReturnRepository.get(zReference, reportingPeriod.taxYear, reportingPeriod.month)
      } yield MonthlyReturnReportingStatus(
        reportingWindowOpen = reportingWindow.isOpen,
        reportingWindowStart = reportingWindow.windowStart,
        reportingWindowEnd = reportingWindow.windowEnd,
        resolvedAt = reportingWindow.instant,
        taxYear = reportingPeriod.taxYear,
        month = reportingPeriod.month,
        obligationClosed = obligationStatus.obligationAlreadyMet,
        monthlyReturnDeclared = monthlyReturn.exists(_.hasDeclaration)
      )
    }
}
