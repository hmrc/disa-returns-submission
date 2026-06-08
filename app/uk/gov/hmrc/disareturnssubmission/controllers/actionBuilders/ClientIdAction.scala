package uk.gov.hmrc.disareturnssubmission.controllers.actionBuilders

import com.google.inject.Inject
import jakarta.inject.Singleton
import play.api.Logging
import play.api.libs.json.Json
import play.api.mvc.Results.BadRequest
import play.api.mvc.{ActionRefiner, Request, Result}
import uk.gov.hmrc.disareturnssubmission.models.common.{BadRequestErr, DeclarationRequest, ErrorResponse}

import scala.concurrent.{ExecutionContext, Future}

class ClientIdAction @Inject() (implicit ec: ExecutionContext) extends ActionRefiner[Request, DeclarationRequest] with Logging {

  private val ClientIdHeader = "X-Client-ID"
  override protected def executionContext: ExecutionContext = ec

  override def refine[A](request: Request[A]): Future[Either[Result, DeclarationRequest[A]]] = {
    val optionClientId = request.headers.get(ClientIdHeader)
    optionClientId match {
      case Some(clientId) =>
        Future.successful(Right(DeclarationRequest(request, clientId)))
      case None =>
        logger.warn("Client ID missing from request header")
        Future.successful(Left(BadRequest(Json.toJson(BadRequestErr(message = "Missing required header: X-Client-ID"): ErrorResponse))))
    }
  }
}
