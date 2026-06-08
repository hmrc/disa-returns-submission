package uk.gov.hmrc.disareturnssubmission.models.common

import play.api.Logging
import uk.gov.hmrc.http.UpstreamErrorResponse

object UpstreamErrorMapper extends Logging {

  def mapToErrorResponse(err: UpstreamErrorResponse): ErrorResponse = {
    logger.warn(s"Received upstream error: status=${err.statusCode}, message='${err.message}'")
    err match {
      case UpstreamErrorResponse(_, 401, _, _) =>
        logger.info("Mapping 401 to Unauthorised")
        UnauthorisedErr
      case UpstreamErrorResponse(_, 500, _, _) | UpstreamErrorResponse(_, 502, _, _) | UpstreamErrorResponse(_, 503, _, _) =>
        logger.error(s"Mapping ${err.statusCode} to InternalServerError")
        InternalServerErr()

      case UpstreamErrorResponse(_, statusCode, _, _) if statusCode >= 400 =>
        logger.error(s"Unhandled upstream error with status=$statusCode, mapping to InternalServerError")
        InternalServerErr()

      case _ =>
        logger.error(s"Unhandled upstream error, mapping to InternalServerError")
        InternalServerErr()
    }
  }
}
