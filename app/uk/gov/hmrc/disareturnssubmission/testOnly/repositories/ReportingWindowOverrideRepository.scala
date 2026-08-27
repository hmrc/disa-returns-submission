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

package uk.gov.hmrc.disareturnssubmission.testOnly.repositories

import org.mongodb.scala.model.{Filters, IndexModel, IndexOptions, Indexes, ReplaceOptions}
import uk.gov.hmrc.disareturnssubmission.config.AppConfig
import uk.gov.hmrc.disareturnssubmission.testOnly.models.{ReportingWindowOverride, ReportingWindowOverrideRequest}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository

import java.time.temporal.ChronoUnit
import java.time.{Clock, Instant}
import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ReportingWindowOverrideRepository @Inject() (
  mongoComponent: MongoComponent,
  appConfig: AppConfig,
  clock: Clock
)(implicit ec: ExecutionContext)
    extends PlayMongoRepository[ReportingWindowOverride](
      mongoComponent = mongoComponent,
      collectionName = "reportingWindowOverrides",
      domainFormat = ReportingWindowOverride.format,
      indexes = Seq(
        IndexModel(
          keys = Indexes.ascending("expiresAt"),
          indexOptions = IndexOptions()
            .name("expiresAtTtlIdx")
            .expireAfter(0, TimeUnit.SECONDS)
        )
      ),
      replaceIndexes = true
    ) {

  def set(zReference: String, request: ReportingWindowOverrideRequest): Future[Unit] = {
    val now              = Instant.now(clock)
    val expiresAt        = Instant.now().plus(appConfig.reportingWindowOverrideTtlHours.toLong, ChronoUnit.HOURS)
    val overrideDocument = ReportingWindowOverride(
      _id = zReference,
      startDate = request.startDate,
      endDate = request.endDate,
      expiresAt = expiresAt,
      updatedAt = now
    )

    collection
      .replaceOne(
        Filters.eq("_id", zReference),
        overrideDocument,
        ReplaceOptions().upsert(true)
      )
      .toFuture()
      .map(_ => ())
  }

  def getActive(zReference: String): Future[Option[ReportingWindowOverride]] =
    collection
      .find(Filters.eq("_id", zReference))
      .first()
      .toFutureOption()
      .map(_.filter(_.expiresAt.isAfter(Instant.now(clock))))

  def delete(zReferences: Seq[String]): Future[Unit] =
    collection
      .deleteMany(Filters.in("_id", zReferences: _*))
      .toFuture()
      .map(_ => ())
}
