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

import uk.gov.hmrc.disareturnssubmission.services.{ResolvedInstant, SystemClock, TimeSource}
import uk.gov.hmrc.disareturnssubmission.testOnly.models.TestOverrideDocument
import uk.gov.hmrc.disareturnssubmission.testOnly.repositories.TestOverrideRepository

import java.time.{Instant, ZoneOffset}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class OverrideTimeSource @Inject() (
  systemClock: SystemClock,
  repository: TestOverrideRepository
)(implicit ec: ExecutionContext)
    extends TimeSource {

  override def instant(zReference: String): Future[Instant] =
    resolve(zReference).map(_.instant)

  override def resolve(zReference: String): Future[ResolvedInstant] =
    repository.getActive(zReference).flatMap(resolve(zReference, _))

  def resolve(zReference: String, aggregate: Option[TestOverrideDocument]): Future[ResolvedInstant] =
    aggregate match {
      case Some(aggregate) if aggregate.clock.nonEmpty =>
        Future.successful(
          ResolvedInstant(aggregate.clock.get.date.atStartOfDay(ZoneOffset.UTC).toInstant, overridden = true)
        )
      case _                                           => systemClock.resolve(zReference)
    }
}
