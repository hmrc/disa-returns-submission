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
import uk.gov.hmrc.disareturnssubmission.services.{ReportingWindowService, ResolvedReportingWindow}
import uk.gov.hmrc.disareturnssubmission.testOnly.OverrideTimeSource
import uk.gov.hmrc.disareturnssubmission.testOnly.repositories.TestOverrideRepository

import java.time.Instant
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class OverrideReportingWindowService @Inject() (
  appConfig: AppConfig,
  timeSource: OverrideTimeSource,
  repository: TestOverrideRepository
)(implicit ec: ExecutionContext)
    extends ReportingWindowService(appConfig, timeSource) {

  override def resolve(zReference: String): Future[ResolvedReportingWindow] =
    repository.getActive(zReference).flatMap { aggregate =>
      timeSource.resolve(zReference, aggregate).map { resolved =>
        val open = aggregate.flatMap(_.reportingWindow) match {
          case Some(window) =>
            !resolved.instant.isBefore(window.startDate) && !resolved.instant.isAfter(window.endDate)
          case None         => isDefaultReportingWindowOpenAt(resolved.instant)
        }
        ResolvedReportingWindow(resolved.instant, open)
      }
    }

  override def isOpenAt(zReference: String, instant: Instant): Future[Boolean] =
    repository.getActive(zReference).map { aggregate =>
      aggregate.flatMap(_.reportingWindow) match {
        case Some(window) => !instant.isBefore(window.startDate) && !instant.isAfter(window.endDate)
        case None         => isDefaultReportingWindowOpenAt(instant)
      }
    }
}
