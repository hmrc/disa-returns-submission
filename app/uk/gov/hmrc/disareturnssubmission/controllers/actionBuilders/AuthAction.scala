package uk.gov.hmrc.disareturnssubmission.controllers.actionBuilders

import play.api.Logging
import play.api.libs.json.Json
import play.api.mvc.Results.{InternalServerError, Unauthorized}
import play.api.mvc.*
import uk.gov.hmrc.auth.core.*
import uk.gov.hmrc.auth.core.retrieve.v2.Retrievals.authorisedEnrolments
import uk.gov.hmrc.disareturnssubmission.models.common.{InternalServerErr, UnauthorisedErr}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.http.HeaderCarrierConverter

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

class AuthAction @Inject() (ac: AuthConnector, cc: ControllerComponents)(implicit val ec: ExecutionContext) {

  private val auth = new AuthorisedFunctions {
    override def authConnector: AuthConnector = ac
  }

  def apply(zRef: String): ActionBuilder[Request, AnyContent] =
    new ActionBuilder[Request, AnyContent] with Logging {

      override def parser:                     BodyParser[AnyContent] = cc.parsers.defaultBodyParser
      override protected def executionContext: ExecutionContext       = cc.executionContext

      override def invokeBlock[A](request: Request[A], block: Request[A] => Future[Result]): Future[Result] = {
        implicit val hc: HeaderCarrier = HeaderCarrierConverter.fromRequest(request)

        auth.authorised(Enrolment(enrolmentKey)).retrieve(authorisedEnrolments) { enrolments =>
          val zRefMatchesEnrolment = enrolments
            .getEnrolment(enrolmentKey)
            .fold(false)(_.getIdentifier(identifierKey).exists(_.value == zRef))

          if (zRefMatchesEnrolment) block(request)
          else throw InternalError("Z-Ref does not match enrolment.")
        } recover {
          case ex: AuthorisationException =>
            logger.warn(s"Authorization failed. Error: ${ex.reason}")
            Unauthorized(Json.toJson(UnauthorisedErr))

          case ex =>
            logger.warn(s"Auth request failed with unexpected exception: $ex")
            InternalServerError(Json.toJson(InternalServerErr()))
        }
      }
    }

  private val enrolmentKey  = "HMRC-DISA-ORG"
  private val identifierKey = "ZREF"
}
