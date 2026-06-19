package uk.gov.hmrc.disareturnssubmission.connectors

import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.ByteString
import play.api.http.Status.OK
import uk.gov.hmrc.http.{HttpResponse, Retries, UpstreamErrorResponse}
import uk.gov.hmrc.http.client.{RequestBuilder, readStreamHttpResponse}

import scala.concurrent.{ExecutionContext, Future}

trait BaseConnector extends Retries {

  extension (requestBuilder: RequestBuilder) {
    def executeAsStream(using m: Materializer, ec: ExecutionContext): Future[Source[ByteString, ?]] =
      requestBuilder
        .stream[HttpResponse]
        .flatMap { response =>
          response.status match {
            case OK => Future.successful(response.bodyAsSource)
            case _  => response.errorFromStream
          }
        }
  }

  extension (response: HttpResponse) {
    private def errorFromStream[A](using m: Materializer, ec: ExecutionContext): Future[A] =
      response.bodyAsSource
        .reduce(_ ++ _)
        .map(_.utf8String)
        .runWith(Sink.head)
        .flatMap { result =>
          Future.failed(UpstreamErrorResponse(result, response.status))
        }
  }

  protected def retryCondition: PartialFunction[Exception, Boolean] = {
    case UpstreamErrorResponse.Upstream5xxResponse(_) =>
      true
  }

}
