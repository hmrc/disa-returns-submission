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

import play.api.Logging
import play.api.libs.json.{Json, OWrites}
import uk.gov.hmrc.disareturnssubmission.config.AppConfig
import uk.gov.hmrc.disareturnssubmission.models.audit.NpsDeclarationSubmittedDetailEvent
import uk.gov.hmrc.disareturnssubmission.models.monthly.MonthlyReturn
import uk.gov.hmrc.play.audit.http.connector.AuditResult.{Disabled, Failure, Success}
import uk.gov.hmrc.play.audit.http.connector.{AuditConnector, AuditResult}
import uk.gov.hmrc.play.audit.model.ExtendedDataEvent

import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AuditService @Inject() (
  connector: AuditConnector,
  appConfig: AppConfig
)(implicit ec: ExecutionContext)
    extends Logging {

  def auditNpsDeclarationSubmitted(
    monthlyReturn: MonthlyReturn,
    failureReason: Option[String]
  ): Future[Unit] =
    sendEvent(
      npsDeclarationSubmitted,
      NpsDeclarationSubmittedDetailEvent(
        internalReturnId = monthlyReturn.submissionId.toString,
        period = reportingPeriod(monthlyReturn.taxYear, monthlyReturn.month),
        submissionStatus = failureReason.fold(successStatus)(_ => failureStatus),
        failureReason = failureReason
      )
    )

  private def sendEvent[A](auditType: String, detail: A)(implicit writes: OWrites[A]): Future[Unit] = {
    val event = ExtendedDataEvent(
      auditSource = appConfig.appName,
      auditType = auditType,
      detail = Json.toJsObject(detail)
    )

    connector
      .sendExtendedEvent(event)
      .map(logResponse(_, auditType))
  }

  private def reportingPeriod(taxYear: String, month: Int): String = {
    val startYear = taxYear.take(4).toInt
    val year      = if (month >= 4) startYear else startYear + 1
    YearMonth.of(year, month).format(DateTimeFormatter.ofPattern("MMMM uuuu", Locale.UK))
  }

  private def logResponse(result: AuditResult, auditType: String): Unit =
    result match {
      case Success         => logger.info(s"$auditType audit successful")
      case Failure(err, _) => logger.warn(s"$auditType Audit Error, message: $err")
      case Disabled        => logger.warn(s"$auditType failure - auditing disabled")
    }
}

private val npsDeclarationSubmitted = "NPSDeclarationSubmitted"
private val successStatus           = "Success"
private val failureStatus           = "Failure"
