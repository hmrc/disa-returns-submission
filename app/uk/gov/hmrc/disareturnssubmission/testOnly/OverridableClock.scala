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

package uk.gov.hmrc.disareturnssubmission.testOnly

import uk.gov.hmrc.disareturnssubmission.services.{SystemClock, TimeSource}
import uk.gov.hmrc.disareturnssubmission.testOnly.repositories.ClockOverrideRepository

import java.time.{Instant, LocalDate, ZoneOffset}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class OverridableClock @Inject() (
  systemClock: SystemClock,
  repository: ClockOverrideRepository
)(implicit ec: ExecutionContext)
    extends TimeSource {

  override def instant(zReference: String): Future[Instant] =
    repository.getActive(zReference).flatMap {
      case Some(clockOverride) => Future.successful(clockOverride.instant)
      case None                => systemClock.instant(zReference)
    }

  def setDate(zReference: String, date: LocalDate): Future[Unit] =
    repository.set(zReference, date.atStartOfDay(ZoneOffset.UTC).toInstant)

  def reset(zReference: String): Future[Unit] =
    repository.delete(zReference)
}
