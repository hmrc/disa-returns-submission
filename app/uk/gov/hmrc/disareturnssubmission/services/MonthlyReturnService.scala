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
import uk.gov.hmrc.disareturnssubmission.config.AppConfig
import uk.gov.hmrc.disareturnssubmission.models.MonthlyReturn
import uk.gov.hmrc.disareturnssubmission.repositories.MonthlyReturnRepository
import uk.gov.hmrc.disareturnssubmission.repositories.DeclareMonthlyReturnRepositoryResult
import uk.gov.hmrc.disareturnssubmission.services.CreateMonthlyReturnResult.*

import java.time.{Clock, LocalDate}
import java.util.UUID
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class MonthlyReturnService @Inject() (
  monthlyReturnRepository: MonthlyReturnRepository,
  appConfig: AppConfig,
  clock: Clock
)(implicit ec: ExecutionContext)
    extends Logging {

  def create(
    zReference: String,
    taxYear: String,
    month: Int,
    nilReturn: Boolean,
    submissionId: UUID
  ): Future[CreateMonthlyReturnResult] =

    monthlyReturnRepository
      .create(zReference, taxYear, month, submissionId, nilReturn)
      .map {
        case Some(monthlyReturn) =>
          logger.info(
            s"[MonthlyReturnService][create] Created monthly return for zReference [$zReference], taxYear [$taxYear], month [$month], submissionId [$submissionId], nilReturn [$nilReturn]"
          )
          Created(monthlyReturn.submissionId)

        case None =>
          logger.warn(
            s"[MonthlyReturnService][create] Monthly return already exists for zReference [$zReference], taxYear [$taxYear], month [$month]"
          )
          AlreadyExists
      }
      .recoverWith { case NonFatal(exception) =>
        logger.error(
          s"[MonthlyReturnService][create] Failed to create monthly return for zReference [$zReference], taxYear [$taxYear], month [$month], submissionId [$submissionId], nilReturn [$nilReturn]",
          exception
        )
        Future.failed(exception)
      }

  def declare(zReference: String, taxYear: String, month: Int): Future[DeclareMonthlyReturnResult] =
    if (!isWithinDeclarationPeriod) {
      logger.warn(
        s"[MonthlyReturnService][declare] Declaration period is closed for zReference [$zReference], taxYear [$taxYear], month [$month]"
      )
      Future.successful(DeclareMonthlyReturnResult.OutsideDeclarationPeriod)
    } else {
      monthlyReturnRepository
        .declare(zReference, taxYear, month)
        .map {
          case DeclareMonthlyReturnRepositoryResult.MonthlyReturnDeclared =>
            logger.info(
              s"[MonthlyReturnService][declare] Declared monthly return for zReference [$zReference], taxYear [$taxYear], month [$month]"
            )
            DeclareMonthlyReturnResult.Declared

          case DeclareMonthlyReturnRepositoryResult.MonthlyReturnAlreadyDeclared =>
            logger.warn(
              s"[MonthlyReturnService][declare] Monthly return already declared for zReference [$zReference], taxYear [$taxYear], month [$month]"
            )
            DeclareMonthlyReturnResult.AlreadyDeclared

          case DeclareMonthlyReturnRepositoryResult.MonthlyReturnNotFound =>
            logger.warn(
              s"[MonthlyReturnService][declare] No monthly return found for zReference [$zReference], taxYear [$taxYear], month [$month]"
            )
            DeclareMonthlyReturnResult.MonthlyReturnNotFound
        }
        .recoverWith { case NonFatal(exception) =>
          logger.error(
            s"[MonthlyReturnService][declare] Failed to declare monthly return for zReference [$zReference], taxYear [$taxYear], month [$month]",
            exception
          )
          Future.failed(exception)
        }
    }

  def isWithinDeclarationPeriod: Boolean = {
    val dayOfMonth = LocalDate.now(clock).getDayOfMonth

    if (dayOfMonth >= appConfig.declarationPeriodStart && dayOfMonth <= appConfig.declarationPeriodEnd) { true }
    else { false }
  }
}

sealed trait CreateMonthlyReturnResult

object CreateMonthlyReturnResult {
  final case class Created(submissionId: UUID) extends CreateMonthlyReturnResult
  case object AlreadyExists extends CreateMonthlyReturnResult
}

sealed trait DeclareMonthlyReturnResult

object DeclareMonthlyReturnResult {
  case object Declared extends DeclareMonthlyReturnResult
  case object AlreadyDeclared extends DeclareMonthlyReturnResult
  case object MonthlyReturnNotFound extends DeclareMonthlyReturnResult
  case object OutsideDeclarationPeriod extends DeclareMonthlyReturnResult
}

sealed trait UpdateNilReturnResult

object UpdateNilReturnResult {
  final case class NilReturnUpdated(monthlyReturn: MonthlyReturn) extends UpdateNilReturnResult
  case object MonthlyReturnAlreadyDeclared extends UpdateNilReturnResult
  case object MonthlyReturnNotFound extends UpdateNilReturnResult
}
