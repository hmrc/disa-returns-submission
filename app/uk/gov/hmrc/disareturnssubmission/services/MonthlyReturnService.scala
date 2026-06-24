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
import uk.gov.hmrc.disareturnssubmission.actions.SubmissionStoreAction
import uk.gov.hmrc.disareturnssubmission.config.AppConfig
import uk.gov.hmrc.disareturnssubmission.models.MonthlyReturn
import uk.gov.hmrc.disareturnssubmission.repositories.MonthlyReturnRepository
import uk.gov.hmrc.disareturnssubmission.repositories.DeclareMonthlyReturnRepositoryResult
import uk.gov.hmrc.disareturnssubmission.services.CreateMonthlyReturnResult.*
import uk.gov.hmrc.disareturnssubmission.utils.{Md5Base64, UuidGenerator}

import java.nio.file.Path
import java.time.{Clock, LocalDate}
import java.util.UUID
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class MonthlyReturnService @Inject() (
  monthlyReturnRepository: MonthlyReturnRepository,
  submissionStoreAction: SubmissionStoreAction,
  appConfig: AppConfig,
  clock: Clock,
  uuidGenerator: UuidGenerator
)(implicit ec: ExecutionContext)
    extends Logging
    with Md5Base64 {

  def create(
    zReference: String,
    taxYear: String,
    month: Int,
    nilReturn: Boolean
  ): Future[CreateMonthlyReturnResult] =
    if (!isWithinDeclarationPeriod(taxYear, month)) {
      logger.warn(
        s"[MonthlyReturnService][declare] Monthly return is outside Declaration period or zReference [$zReference], taxYear [$taxYear], month [$month]"
      )
      Future.successful(CreateMonthlyReturnResult.OutsideDeclarationPeriod)
    } else {
      val submissionId = uuidGenerator.randomUuid()

      monthlyReturnRepository
        .create(zReference, taxYear, month, submissionId, nilReturn)
        .flatMap {
          case Some(monthlyReturn) =>
            logger.info(
              s"[MonthlyReturnService][create] Created monthly return for zReference [$zReference], taxYear [$taxYear], month [$month], submissionId [$submissionId], nilReturn [$nilReturn]"
            )
            Future.successful(Created(monthlyReturn.submissionId))

          case None =>
            logger.warn(
              s"[MonthlyReturnService][create] Monthly return already exists for zReference [$zReference], taxYear [$taxYear], month [$month]"
            )
            monthlyReturnRepository.get(zReference, taxYear, month).map {
              case Some(monthlyReturn) => AlreadyExists(monthlyReturn.submissionId)
              case None                =>
                throw new IllegalStateException(
                  s"Monthly return duplicate reported but no record found for zReference [$zReference], taxYear [$taxYear], month [$month]"
                )
            }
        }
        .recoverWith { case NonFatal(exception) =>
          logger.error(
            s"[MonthlyReturnService][create] Failed to create monthly return for zReference [$zReference], taxYear [$taxYear], month [$month], submissionId [$submissionId], nilReturn [$nilReturn]",
            exception
          )
          Future.failed(exception)
        }
    }

  def get(zReference: String, taxYear: String, month: Int): Future[Option[MonthlyReturn]] =

    monthlyReturnRepository
      .get(zReference, taxYear, month)
      .map { maybeMonthlyReturn =>
        maybeMonthlyReturn match {
          case Some(_) =>
            logger.info(
              s"[MonthlyReturnService][get] Found monthly return for zReference [$zReference], taxYear [$taxYear], month [$month]"
            )
          case None    =>
            logger.warn(
              s"[MonthlyReturnService][get] No monthly return found for zReference [$zReference], taxYear [$taxYear], month [$month]"
            )
        }

        maybeMonthlyReturn
      }
      .recoverWith { case NonFatal(exception) =>
        logger.error(
          s"[MonthlyReturnService][get] Failed to get monthly return for zReference [$zReference], taxYear [$taxYear], month [$month]",
          exception
        )
        Future.failed(exception)
      }

  def declare(zReference: String, taxYear: String, month: Int): Future[DeclareMonthlyReturnResult] =
    if (!isWithinDeclarationPeriod(taxYear, month)) {
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

  def storeSubmission(zReference: String, taxYear: String, month: Int, bodyPath: Path): Future[SubmitReturnResult] =

    get(zReference, taxYear, month).flatMap {
      case Some(monthlyReturn) => submissionStoreAction.store(bodyPath, monthlyReturn)
      case _                   => Future.successful(SubmitReturnResult.MonthlyReturnNotFound)
    }

  private def isWithinDeclarationPeriod(year: String, month: Int): Boolean = {
    val nowClock   = LocalDate.now(clock)
    val dayOfMonth = nowClock.getDayOfMonth

    val startYear    = year.split("-").head.toInt
    val taxYearStart = LocalDate.of(startYear, 4, 6)
    val taxYearEnd   = LocalDate.of(startYear + 1, 4, 5)

    val isDayWithinDeclarationPeriod =
      dayOfMonth >= appConfig.declarationPeriodStart && dayOfMonth <= appConfig.declarationPeriodEnd

    val isCurrentMonth   = nowClock.getMonthValue == month
    val isCurrentTaxYear = nowClock.isAfter(taxYearStart) && nowClock.isBefore(taxYearEnd)

    isDayWithinDeclarationPeriod && isCurrentMonth && isCurrentTaxYear
  }
}

sealed trait CreateMonthlyReturnResult

object CreateMonthlyReturnResult {
  final case class Created(submissionId: UUID) extends CreateMonthlyReturnResult
  final case class AlreadyExists(submissionId: UUID) extends CreateMonthlyReturnResult
  case object OutsideDeclarationPeriod extends CreateMonthlyReturnResult
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

sealed trait SubmitReturnResult

object SubmitReturnResult {
  case object UpdateSuccessful extends SubmitReturnResult
  case object MonthlyReturnNotFound extends SubmitReturnResult
  case object NotUpdatedInRepository extends SubmitReturnResult
  case object NoBody extends SubmitReturnResult
}
