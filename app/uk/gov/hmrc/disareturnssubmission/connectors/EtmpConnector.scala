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
import uk.gov.hmrc.disareturnssubmission.config.AppConfig
import uk.gov.hmrc.disareturnssubmission.models.monthly.MonthlyReturnObligationStatus
import uk.gov.hmrc.http.HttpReads.Implicits.*
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.*

import javax.inject.*
import scala.concurrent.*

@Singleton
class EtmpConnector @Inject() (
  httpClient: HttpClientV2,
  appConfig: AppConfig,
  override val configuration: Config,
  override val actorSystem: ActorSystem
)(implicit ec: ExecutionContext)
    extends BaseConnector {

  def getMonthlyReturnObligationStatus(
    zReference: String
  )(implicit hc: HeaderCarrier): Future[MonthlyReturnObligationStatus] =
    retryFor[MonthlyReturnObligationStatus]("get ETMP monthly return obligation status")(retryCondition) {
      httpClient
        .get(url"${appConfig.etmpBaseUrl}/etmp/check-obligation-status/$zReference")(
          hc.copy(authorization = None)
        )
        .execute[MonthlyReturnObligationStatus]
    }
}
