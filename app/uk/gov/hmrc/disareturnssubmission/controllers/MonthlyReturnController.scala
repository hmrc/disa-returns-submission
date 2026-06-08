package uk.gov.hmrc.disareturnssubmission.controllers

import play.api.Logging
import play.api.libs.json.Json
import play.api.mvc.{ControllerComponents, Result}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.play.bootstrap.controller.WithJsonBody
import uk.gov.hmrc.disareturnssubmission.validators.ValidationHelper

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class MonthlyReturnController @Inject() (
  cc: ControllerComponents
  // monthlyReturnService: MonthlyReturnService
)(implicit ec: ExecutionContext)
    extends BackendController(cc)
    with WithJsonBody
    with Logging {

  private def withValidMonthlyReturnParams(
    zReference: String,
    taxYear: String,
    month: String
  )(block: (String, String, Int) => Future[Result]): Future[Result] =
    ValidationHelper.validateParams(zReference, taxYear, month) match {
      case Right((validZReference, validTaxYear, validMonth)) =>
        block(validZReference, validTaxYear, validMonth)

      case Left(errorMessage) =>
        logger.warn(
          s"[MonthlyReturnController][withValidMonthlyReturnParams] Invalid monthly return request parameters for zReference [$zReference], taxYear [$taxYear], month [$month]: [$errorMessage]"
        )
        Future.successful(BadRequest(Json.obj("message" -> errorMessage)))
    }

}
