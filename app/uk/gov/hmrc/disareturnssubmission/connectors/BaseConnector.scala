package uk.gov.hmrc.disareturnssubmission.connectors

import cats.data.EitherT
import play.api.Logging
import play.api.http.Status.INTERNAL_SERVER_ERROR
import uk.gov.hmrc.http.{HttpException, HttpResponse, UpstreamErrorResponse}

import scala.concurrent.{ExecutionContext, Future}

trait BaseConnector  extends Logging {

  implicit val ec: ExecutionContext

  def read[A <: HttpResponse](response: Future[Either[UpstreamErrorResponse, A]], context: String): EitherT[Future, UpstreamErrorResponse, A] = {
    val recoveredResponse: Future[Either[UpstreamErrorResponse, A]] = response.recover {
      case ex: HttpException =>
        logger.error(s"[$context] ${ex.getMessage}")
        Left(UpstreamErrorResponse(s"Unexpected error: ${ex.getMessage}", INTERNAL_SERVER_ERROR, INTERNAL_SERVER_ERROR))
      case ex =>
        logger.error(s"[$context] Unexpected error: ${ex.getMessage}", ex)
        Left(UpstreamErrorResponse(s"Unexpected error: ${ex.getMessage}", INTERNAL_SERVER_ERROR, INTERNAL_SERVER_ERROR))
    }

    EitherT(recoveredResponse.map {
      case Right(httpResponse) if httpResponse.status >= 400 =>
        val msg = s"Received error status ${httpResponse.status} with body: ${httpResponse.body}"
        logger.warn(s"[$context] $msg")
        Left(UpstreamErrorResponse(msg, httpResponse.status, httpResponse.status))
      case Right(success) =>
        val msg = s"Received successful response with status: ${success.status} with body: ${success.body}"
        logger.info(s"[$context] $msg")
        Right(success)
      case Left(error) =>
        logger.error(s"[$context] ${error.message}")
        Left(error)
    })
  }
}