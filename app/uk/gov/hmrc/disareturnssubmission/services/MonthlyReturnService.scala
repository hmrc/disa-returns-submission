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
import uk.gov.hmrc.disareturnssubmission.models.MonthlyReturn
import uk.gov.hmrc.disareturnssubmission.repositories.{DeclareMonthlyReturnRepositoryResult, MonthlyReturnRepository}
import uk.gov.hmrc.disareturnssubmission.services.CreateMonthlyReturnResult.*
import uk.gov.hmrc.disareturnssubmission.utils.UuidGenerator

import java.nio.file.Path
import java.time.{Clock, LocalDate, YearMonth}
import java.util.UUID
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class MonthlyReturnService @Inject() (
  monthlyReturnRepository: MonthlyReturnRepository,
  submissionStoreAction: SubmissionStoreAction,
  reportingWindowService: ReportingWindowService,
  clock: Clock,
  uuidGenerator: UuidGenerator
)(implicit ec: ExecutionContext)
    extends Logging {

  def create(
    zReference: String,
    taxYear: String,
    month: Int,
    nilReturn: Boolean
  ): Future[CreateMonthlyReturnResult] =
    if (!isWithinDeclarationPeriod(taxYear, month)) {
      logger.warn(
        s"[MonthlyReturnService][create] Monthly return is outside Declaration period for zReference [$zReference], taxYear [$taxYear], month [$month]"
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

  def declare(
    zReference: String,
    taxYear: String,
    month: Int,
    nilReturn: Boolean,
    pendingSubmissionIds: List[String] = Nil
  ): Future[DeclareMonthlyReturnResult] =
    if (nilReturn && pendingSubmissionIds.nonEmpty) {
      logger.warn(
        s"[MonthlyReturnService][declare] Pending submissions cannot be supplied for a nil return for zReference [$zReference], taxYear [$taxYear], month [$month]"
      )
      Future.successful(DeclareMonthlyReturnResult.PendingSubmissionsForNilReturn)
    } else if (!isWithinDeclarationPeriod(taxYear, month)) {
      logger.warn(
        s"[MonthlyReturnService][declare] Declaration period is closed for zReference [$zReference], taxYear [$taxYear], month [$month]"
      )
      Future.successful(DeclareMonthlyReturnResult.OutsideDeclarationPeriod)
    } else if (nilReturn) {
      declareNilReturn(zReference, taxYear, month)
    } else {
      declareExistingReturn(zReference, taxYear, month, pendingSubmissionIds)
    }

  private def declareNilReturn(zReference: String, taxYear: String, month: Int): Future[DeclareMonthlyReturnResult] =
    monthlyReturnRepository
      .declareNilReturn(zReference, taxYear, month)
      .flatMap {
        case DeclareMonthlyReturnRepositoryResult.MonthlyReturnDeclared =>
          logger.info(
            s"[MonthlyReturnService][declareNilReturn] Updated and declared nil return for zReference [$zReference], taxYear [$taxYear], month [$month]"
          )
          Future.successful(DeclareMonthlyReturnResult.Declared)

        case DeclareMonthlyReturnRepositoryResult.MonthlyReturnAlreadyDeclared =>
          logger.warn(
            s"[MonthlyReturnService][declareNilReturn] Monthly return already declared for zReference [$zReference], taxYear [$taxYear], month [$month]"
          )
          Future.successful(DeclareMonthlyReturnResult.AlreadyDeclared)

        case DeclareMonthlyReturnRepositoryResult.MonthlyReturnNotFound =>
          logger.info(
            s"[MonthlyReturnService][declareNilReturn] No monthly return found for zReference [$zReference], taxYear [$taxYear], month [$month]. Creating nil return before declaration"
          )

          monthlyReturnRepository
            .create(zReference, taxYear, month, uuidGenerator.randomUuid(), nilReturn = true)
            .flatMap {
              case Some(_) =>
                monthlyReturnRepository.declare(zReference, taxYear, month, pendingSubmissionIds = Nil).map {
                  case DeclareMonthlyReturnRepositoryResult.MonthlyReturnDeclared =>
                    logger.info(
                      s"[MonthlyReturnService][declareNilReturn] Created and declared nil return for zReference [$zReference], taxYear [$taxYear], month [$month]"
                    )
                    DeclareMonthlyReturnResult.Declared

                  case DeclareMonthlyReturnRepositoryResult.MonthlyReturnAlreadyDeclared =>
                    logger.warn(
                      s"[MonthlyReturnService][declareNilReturn] Created nil return but monthly return was already declared for zReference [$zReference], taxYear [$taxYear], month [$month]"
                    )
                    DeclareMonthlyReturnResult.AlreadyDeclared

                  case DeclareMonthlyReturnRepositoryResult.MonthlyReturnNotFound =>
                    logger.warn(
                      s"[MonthlyReturnService][declareNilReturn] Unable to declare nil return after create for zReference [$zReference], taxYear [$taxYear], month [$month]"
                    )
                    DeclareMonthlyReturnResult.MonthlyReturnNotFound
                }
              case None    =>
                logger.error(
                  s"[MonthlyReturnService][declareNilReturn] Failed to create nil return for zReference [$zReference], taxYear [$taxYear], month [$month]"
                )
                Future.failed(new RuntimeException("Failed to create nil return monthly return"))
            }
      }
      .recoverWith { case NonFatal(exception) =>
        logger.error(
          s"[MonthlyReturnService][declareNilReturn] Failed to declare nil return for zReference [$zReference], taxYear [$taxYear], month [$month]",
          exception
        )
        Future.failed(exception)
      }

  private def declareExistingReturn(
    zReference: String,
    taxYear: String,
    month: Int,
    pendingSubmissionIds: List[String]
  ): Future[DeclareMonthlyReturnResult] =
    monthlyReturnRepository
      .declare(zReference, taxYear, month, pendingSubmissionIds)
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

  def storeSubmission(
    zReference: String,
    taxYear: String,
    month: Int,
    submissionId: String,
    bodyPath: Path
  ): Future[SubmitReturnResult] =

    get(zReference, taxYear, month)
      .flatMap {
        case Some(monthlyReturn) if monthlyReturn.hasStoredSubmission(submissionId) =>
          logger.warn(
            s"[MonthlyReturnService][storeSubmission] Submission [$submissionId] is already stored for zReference [$zReference], taxYear [$taxYear], month [$month]"
          )
          Future.successful(SubmitReturnResult.SubmissionConflict)

        case Some(monthlyReturn) if !monthlyReturn.hasCreatedSubmission(submissionId) =>
          logger.warn(
            s"[MonthlyReturnService][storeSubmission] Monthly return cannot accept submission [$submissionId] for zReference [$zReference], taxYear [$taxYear], month [$month]"
          )
          Future.successful(SubmitReturnResult.SubmissionConflict)

        case Some(monthlyReturn) =>
          submissionStoreAction.store(bodyPath, monthlyReturn, submissionId).map {
            case SubmitReturnResult.UpdateSuccessful =>
              logger.info(
                s"[MonthlyReturnService][storeSubmission] Stored submission [$submissionId] for zReference [$zReference], taxYear [$taxYear], month [$month]"
              )
              SubmitReturnResult.UpdateSuccessful

            case SubmitReturnResult.SubmissionConflict =>
              logger.warn(
                s"[MonthlyReturnService][storeSubmission] Submission [$submissionId] conflicted with the current monthly return state for zReference [$zReference], taxYear [$taxYear], month [$month]"
              )
              SubmitReturnResult.SubmissionConflict

            case SubmitReturnResult.NoBody =>
              logger.warn(
                s"[MonthlyReturnService][storeSubmission] Submission body was empty for zReference [$zReference], taxYear [$taxYear], month [$month]"
              )
              SubmitReturnResult.NoBody

            case SubmitReturnResult.MonthlyReturnNotFound =>
              logger.warn(
                s"[MonthlyReturnService][storeSubmission] Submission store action could not find monthly return for zReference [$zReference], taxYear [$taxYear], month [$month]"
              )
              SubmitReturnResult.MonthlyReturnNotFound
          }

        case _ =>
          logger.warn(
            s"[MonthlyReturnService][storeSubmission] No monthly return found for zReference [$zReference], taxYear [$taxYear], month [$month]"
          )
          Future.successful(SubmitReturnResult.MonthlyReturnNotFound)
      }
      .recoverWith { case NonFatal(exception) =>
        logger.error(
          s"[MonthlyReturnService][storeSubmission] Failed to store submission [$submissionId] for zReference [$zReference], taxYear [$taxYear], month [$month]",
          exception
        )
        Future.failed(exception)
      }

  private def isWithinDeclarationPeriod(taxYear: String, month: Int): Boolean = {
    val today               = LocalDate.now(clock)
    val previousMonthPeriod = YearMonth.from(today).minusMonths(1)

    val isPreviousMonth   = previousMonthPeriod.getMonthValue == month
    val isPreviousTaxYear = taxYearFor(previousMonthPeriod) == taxYear

    reportingWindowService.isOpen && isPreviousMonth && isPreviousTaxYear
  }

  private def taxYearFor(period: YearMonth): String = {
    val startYear = if (period.getMonthValue >= 4) period.getYear else period.getYear - 1
    f"$startYear%04d-${(startYear + 1) % 100}%02d"
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
  case object PendingSubmissionsForNilReturn extends DeclareMonthlyReturnResult
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
  case object SubmissionConflict extends SubmitReturnResult
  case object NoBody extends SubmitReturnResult
}
