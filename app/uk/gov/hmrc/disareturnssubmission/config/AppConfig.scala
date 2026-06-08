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

package uk.gov.hmrc.disareturnssubmission.config

import javax.inject.{Inject, Singleton}
import play.api.Configuration
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

@Singleton
class AppConfig @Inject() (
  config: Configuration,
  servicesConfig: ServicesConfig
) {

  val appName: String = config.get[String]("appName")

  val internalAuthService: String = servicesConfig.baseUrl("internal-auth")
  val internalAuthToken: String   = config.get[String]("internal-auth.token")

  lazy val etmpBaseUrl: String = servicesConfig.baseUrl(serviceName = "etmp")
  lazy val ppnsBaseUrl: String = servicesConfig.baseUrl(serviceName = "ppns")
  lazy val npsBaseUrl: String = servicesConfig.baseUrl(serviceName = "nps")
  lazy val selfHost: String = servicesConfig.baseUrl(serviceName = "self")

  lazy val timeToLive: Int = servicesConfig.getInt("mongodb.timeToLive")

  private lazy val returnResultsRecordsPerPage: Int = servicesConfig.getInt("returnResultsRecordsPerPage")

  def getNoOfPagesForReturnResults(noOfRecords: Int): Option[Int] =
    if (noOfRecords >= 0) Some(math.ceil(noOfRecords.toDouble / returnResultsRecordsPerPage).toInt)
    else None
}
