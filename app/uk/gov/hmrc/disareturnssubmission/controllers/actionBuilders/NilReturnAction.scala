package uk.gov.hmrc.disareturnssubmission.controllers.actionBuilders

import com.google.inject.Inject
import jakarta.inject.Singleton
import play.api.Logging
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.*
import play.api.mvc.Results.BadRequest
import uk.gov.hmrc.disareturnssubmission.models.common.{DeclarationRequest, MalformedJsonFailureErr}
import uk.gov.hmrc.disareturnssubmission.models.declaration.ReportingNilReturn

import scala.concurrent.{ExecutionContext, Future}

class NilReturnAction @Inject() (implicit ec: ExecutionContext)
    extends ActionRefiner[DeclarationRequest, DeclarationRequest]
    with Logging {

  override protected def executionContext: ExecutionContext = ec

  override def refine[A](request: DeclarationRequest[A]): Future[Either[Result, DeclarationRequest[A]]] = Future {

    val nilReturnReported: Either[Result, Boolean] = request.body match {
      case jsOpt: Option[JsValue] =>
        jsOpt match {
          case Some(js: JsValue) =>
            js.validate[ReportingNilReturn]
              .fold(
                errors =>
                  Left(
                    BadRequest(Json.toJson(MalformedJsonFailureErr(message = "Request body contains malformed JSON")))
                  ),
                model => Right(model.nilReturn)
              )
          case None              => Right(false)
        }
      case _                      => Right(false)
    }
    nilReturnReported.map(nr => request.copy(nilReturnReported = nr))
  }
}
