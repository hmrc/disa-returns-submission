package uk.gov.hmrc.disareturnssubmission.services

import play.api.Logging
import uk.gov.hmrc.disareturnssubmission.models.MonthlyReturn
import uk.gov.hmrc.disareturnssubmission.repositories.MonthlyReturnRepository
import uk.gov.hmrc.disareturnssubmission.services.CreateMonthlyReturnResult.*

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class MonthlyReturnService @Inject() (monthlyReturnRepository: MonthlyReturnRepository)(implicit ec: ExecutionContext)
    extends Logging {

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

  def create(zReference: String, taxYear: String, month: Int, nilReturn: Boolean): Future[CreateMonthlyReturnResult] =
    monthlyReturnRepository
      .create(zReference, taxYear, month, nilReturn)
      .map {
        case true =>
          logger.info(
            s"[MonthlyReturnService][create] Created monthly return for zReference [$zReference], taxYear [$taxYear], month [$month], nilReturn [$nilReturn]"
          )
          Created

        case false =>
          logger.warn(
            s"[MonthlyReturnService][create] Monthly return already exists for zReference [$zReference], taxYear [$taxYear], month [$month]"
          )
          AlreadyExists
      }
      .recoverWith { case NonFatal(exception) =>
        logger.error(
          s"[MonthlyReturnService][create] Failed to create monthly return for zReference [$zReference], taxYear [$taxYear], month [$month], nilReturn [$nilReturn]",
          exception
        )
        Future.failed(exception)
      }

  def updateNilReturn(
    zReference: String,
    taxYear: String,
    month: Int,
    nilReturn: Boolean
  ): Future[Option[MonthlyReturn]] =
    monthlyReturnRepository
      .updateNilReturn(zReference, taxYear, month, nilReturn)
      .map { maybeMonthlyReturn =>
        maybeMonthlyReturn match {
          case Some(_) =>
            logger.info(
              s"[MonthlyReturnService][updateNilReturn] Updated nilReturn to [$nilReturn] for zReference [$zReference], taxYear [$taxYear], month [$month]"
            )
          case None    =>
            logger.warn(
              s"[MonthlyReturnService][updateNilReturn] No monthly return found for zReference [$zReference], taxYear [$taxYear], month [$month]"
            )
        }

        maybeMonthlyReturn
      }
      .recoverWith { case NonFatal(exception) =>
        logger.error(
          s"[MonthlyReturnService][updateNilReturn] Failed to update nilReturn to [$nilReturn] for zReference [$zReference], taxYear [$taxYear], month [$month]",
          exception
        )
        Future.failed(exception)
      }
}

sealed trait CreateMonthlyReturnResult

object CreateMonthlyReturnResult {
  case object Created extends CreateMonthlyReturnResult
  case object AlreadyExists extends CreateMonthlyReturnResult
}
