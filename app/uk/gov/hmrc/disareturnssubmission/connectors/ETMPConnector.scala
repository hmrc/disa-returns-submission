package uk.gov.hmrc.disareturnssubmission.connectors

import cats.data.EitherT
import uk.gov.hmrc.disareturnssubmission.config.AppConfig
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse, StringContextOps, UpstreamErrorResponse}

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class ETMPConnector @Inject() (http: HttpClientV2, appConfig: AppConfig)(implicit val ec: ExecutionContext) extends BaseConnector {

  def getReturnsObligationStatus(
                                  zReference:  String
                                )(implicit hc: HeaderCarrier): EitherT[Future, UpstreamErrorResponse, HttpResponse] = {
    val url = s"${appConfig.etmpBaseUrl}/etmp/check-obligation-status/$zReference"
    read(
      http
        .get(url"$url")
        .execute[Either[UpstreamErrorResponse, HttpResponse]],
      context = "ETMPConnector: getReturnsObligationStatus"
    )
  }
  def sendDeclaration(
                       zReference:  String
                     )(implicit hc: HeaderCarrier): EitherT[Future, UpstreamErrorResponse, HttpResponse] = {
    val url = s"${appConfig.etmpBaseUrl}/etmp/declaration/$zReference"
    read(
      http
        .post(url"$url")
        .execute[Either[UpstreamErrorResponse, HttpResponse]],
      context = "ETMPConnector: sendDeclaration"
    )
  }

  def getReportingWindowStatus(implicit hc: HeaderCarrier): EitherT[Future, UpstreamErrorResponse, HttpResponse] = {
    val url = s"${appConfig.etmpBaseUrl}/etmp/check-reporting-window"
    read(
      http
        .get(url"$url")
        .execute[Either[UpstreamErrorResponse, HttpResponse]],
      context = "ETMPConnector: getReportingWindowStatus"
    )
  }
}