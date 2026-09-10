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

import org.mongodb.scala.model.{BulkWriteOptions, Filters, IndexModel, IndexOptions, Indexes, ReplaceOneModel, ReplaceOptions}
import uk.gov.hmrc.disareturnssubmission.config.AppConfig
import uk.gov.hmrc.disareturnssubmission.testOnly.models.{TestOverrideDocument, TestOverrideRequest}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository

import java.time.temporal.ChronoUnit
import java.time.{Clock, Instant}
import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class TestOverrideRepository @Inject() (
  mongoComponent: MongoComponent,
  appConfig: AppConfig,
  clock: Clock
)(implicit ec: ExecutionContext)
    extends PlayMongoRepository[TestOverrideDocument](
      mongoComponent = mongoComponent,
      collectionName = "testOverrides",
      domainFormat = TestOverrideDocument.format,
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

  def replace(zReferences: Seq[String], request: TestOverrideRequest): Future[Unit] = {
    val now       = Instant.now(clock)
    val expiresAt = now.plus(appConfig.testOverrideTtlHours.toLong, ChronoUnit.HOURS)
    val writes    = zReferences.map { zReference =>
      val aggregate = TestOverrideDocument(
        _id = zReference,
        clock = request.clock,
        reportingWindow = request.reportingWindow,
        expiresAt = expiresAt,
        updatedAt = now
      )
      ReplaceOneModel(Filters.eq("_id", zReference), aggregate, ReplaceOptions().upsert(true))
    }

    collection
      .bulkWrite(writes, BulkWriteOptions().ordered(false))
      .toFuture()
      .map(_ => ())
  }

  def getActive(zReference: String): Future[Option[TestOverrideDocument]] =
    collection
      .find(Filters.eq("_id", zReference))
      .first()
      .toFutureOption()
      .map(_.filter(_.expiresAt.isAfter(Instant.now(clock))))

  def delete(zReferences: Seq[String]): Future[Unit] =
    collection.deleteMany(Filters.in("_id", zReferences: _*)).toFuture().map(_ => ())
}
