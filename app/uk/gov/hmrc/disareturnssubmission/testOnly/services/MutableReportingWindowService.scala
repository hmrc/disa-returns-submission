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

package uk.gov.hmrc.disareturnssubmission.testOnly.services

import uk.gov.hmrc.disareturnssubmission.config.AppConfig
import uk.gov.hmrc.disareturnssubmission.services.ReportingWindowService
import uk.gov.hmrc.disareturnssubmission.testOnly.models.ReportingWindowOverrideRequest
import uk.gov.hmrc.disareturnssubmission.testOnly.repositories.ReportingWindowOverrideRepository

import java.time.{Clock, Instant}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class MutableReportingWindowService @Inject() (
  appConfig: AppConfig,
  clock: Clock,
  repository: ReportingWindowOverrideRepository
)(implicit ec: ExecutionContext)
    extends ReportingWindowService(appConfig, clock) {

  override def isOpen(zReference: String): Future[Boolean] =
    repository.getActive(zReference).map {
      case Some(overrideWindow) =>
        val now = Instant.now(clock)
        !now.isBefore(overrideWindow.startDate) && !now.isAfter(overrideWindow.endDate)
      case None                 => isDefaultReportingWindowOpen
    }

  def set(zReference: String, request: ReportingWindowOverrideRequest): Future[Unit] =
    repository.set(zReference, request)

  def delete(zReferences: Seq[String]): Future[Unit] =
    repository.delete(zReferences)
}
