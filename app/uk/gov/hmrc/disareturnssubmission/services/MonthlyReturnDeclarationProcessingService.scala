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
import uk.gov.hmrc.disareturnssubmission.connectors.NpsConnector
import uk.gov.hmrc.disareturnssubmission.models.monthly.*
import uk.gov.hmrc.disareturnssubmission.repositories.MonthlyReturnRepository
import uk.gov.hmrc.disareturnssubmission.services.MonthlyReturnDeclarationProcessingResult.*
import uk.gov.hmrc.http.UpstreamErrorResponse

import javax.inject.*
import scala.concurrent.*
import scala.util.control.NonFatal

@Singleton
class MonthlyReturnDeclarationProcessingService @Inject() (
  repository: MonthlyReturnRepository,
  npsConnector: NpsConnector,
  auditService: AuditService
)(implicit ec: ExecutionContext)
    extends Logging {

  def process(item: MonthlyReturnDeclarationWorkItem): Future[MonthlyReturnDeclarationProcessingResult] =
    repository.get(item.zReference, item.taxYear, item.month).flatMap {
      case Some(monthlyReturn) if monthlyReturn.npsDeclaredOn.isDefined =>
        logger.info(
          s"[MonthlyReturnDeclarationProcessingService][process] Already declared to NPS - skipping declaration for ${loggingContext(item)}"
        )
        Future.successful(Declared)
      case Some(monthlyReturn) if monthlyReturn.hasDeclaration          =>
        if (monthlyReturn.submissions.exists(_.status != MonthlyReturnSubmissionStatus.NpsSubmitted)) {
          Future.successful(PendingSubmissions)
        } else {
          declareToNps(item, monthlyReturn)
        }
      case _                                                            =>
        Future.failed(new IllegalStateException(s"Monthly return missing or not declared for ${loggingContext(item)}"))
    }

  private def declareToNps(
    item: MonthlyReturnDeclarationWorkItem,
    monthlyReturn: MonthlyReturn
  ): Future[MonthlyReturnDeclarationProcessingResult] =
    npsConnector
      .sendDeclaration(item.zReference)
      .recoverWith { case NonFatal(exception) =>
        auditNpsDeclaration(item, monthlyReturn, Some(npsFailureReason(exception)))
        Future.failed(exception)
      }
      .flatMap { _ =>
        auditNpsDeclaration(item, monthlyReturn, None)
        repository
          .markNpsDeclared(item.zReference, item.taxYear, item.month)
          .map { updated =>
            if (!updated) {
              throw new IllegalStateException(s"Could not mark monthly return NPS declared for ${loggingContext(item)}")
            }
            logger.info(
              s"[MonthlyReturnDeclarationProcessingService][declareToNps] Declared to NPS and recorded npsDeclaredOn for ${loggingContext(item)}"
            )
            Declared
          }
          .recoverWith { case NonFatal(exception) =>
            logger.warn(
              s"[MonthlyReturnDeclarationProcessingService][declareToNps] NPS accepted declaration but could not record npsDeclaredOn for ${loggingContext(item)} - declaration may be retried",
              exception
            )
            Future.failed(exception)
          }
      }

  private def auditNpsDeclaration(
    item: MonthlyReturnDeclarationWorkItem,
    monthlyReturn: MonthlyReturn,
    failureReason: Option[String]
  ): Unit =
    auditService
      .auditNpsDeclarationSubmitted(monthlyReturn, failureReason)
      .recover { case NonFatal(exception) =>
        logger.warn(
          s"[MonthlyReturnDeclarationProcessingService][auditNpsDeclaration] Failed to audit NPSDeclarationSubmitted for ${loggingContext(item)}",
          exception
        )
      }

  private def npsFailureReason(exception: Throwable): String =
    exception match {
      case upstream: UpstreamErrorResponse => upstream.message
      case other                           => s"NPS declaration request failed [${other.getClass.getSimpleName}]"
    }

  private def loggingContext(item: MonthlyReturnDeclarationWorkItem): String =
    s"zReference [${item.zReference}], taxYear [${item.taxYear}], month [${item.month}]"
}

sealed trait MonthlyReturnDeclarationProcessingResult

object MonthlyReturnDeclarationProcessingResult {
  case object Declared extends MonthlyReturnDeclarationProcessingResult
  case object PendingSubmissions extends MonthlyReturnDeclarationProcessingResult
}
