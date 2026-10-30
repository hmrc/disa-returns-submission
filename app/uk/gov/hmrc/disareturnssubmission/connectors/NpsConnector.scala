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

package uk.gov.hmrc.disareturnssubmission.connectors

import com.typesafe.config.Config
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import play.api.http.HeaderNames.AUTHORIZATION
import play.api.http.MimeTypes.JSON
import play.api.http.Status.NO_CONTENT
import play.api.libs.ws.*
import uk.gov.hmrc.disareturnssubmission.config.AppConfig
import uk.gov.hmrc.http.HttpReads.Implicits.readRaw
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.*

import javax.inject.*
import scala.concurrent.*

@Singleton
class NpsConnector @Inject() (
  httpClient: HttpClientV2,
  appConfig: AppConfig,
  override val configuration: Config,
  override val actorSystem: ActorSystem
)(implicit ec: ExecutionContext)
    extends BaseConnector {
  private implicit val jsonBodyWritable: BodyWritable[Source[ByteString, ?]] =
    BodyWritable(SourceBody(_), JSON)

  def sendSubmission(zReference: String, body: Source[ByteString, ?]): Future[Unit] =
    retryFor[Unit](s"send NPS submission for zReference [$zReference]")(retryCondition) {
      httpClient
        .post(url"${appConfig.npsMonthlyReturnSubmissionService}/nps/submit/$zReference")(HeaderCarrier())
        .setHeader(AUTHORIZATION -> s"Bearer ${appConfig.npsMonthlyReturnBearerToken}")
        .withBody(body)
        .execute[HttpResponse]
        .flatMap { response =>
          if (response.status == NO_CONTENT) Future.unit
          else
            Future.failed(
              UpstreamErrorResponse(s"NPS submission returned status [${response.status}]", response.status)
            )
        }
    }
}
