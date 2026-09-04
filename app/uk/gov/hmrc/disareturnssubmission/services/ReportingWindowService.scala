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

package uk.gov.hmrc.disareturnssubmission.services

import uk.gov.hmrc.disareturnssubmission.config.AppConfig

import java.time.{Instant, LocalDate, ZoneOffset}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

final case class ResolvedReportingWindow(instant: Instant, isOpen: Boolean)

@Singleton
class ReportingWindowService @Inject() (appConfig: AppConfig, timeSource: TimeSource)(implicit ec: ExecutionContext) {

  def isOpen(zReference: String): Future[Boolean] =
    resolve(zReference).map(_.isOpen)

  def resolve(zReference: String): Future[ResolvedReportingWindow] =
    timeSource.resolve(zReference).map { resolved =>
      ResolvedReportingWindow(resolved.instant, isDefaultReportingWindowOpenAt(resolved.instant))
    }

  def isOpenAt(zReference: String, instant: Instant): Future[Boolean] =
    Future.successful(isDefaultReportingWindowOpenAt(instant))

  def isDefaultReportingWindowOpenAt(instant: Instant): Boolean = {
    val dayOfMonth = LocalDate.ofInstant(instant, ZoneOffset.UTC).getDayOfMonth
    dayOfMonth >= appConfig.declarationPeriodStart && dayOfMonth <= appConfig.declarationPeriodEnd
  }
}
