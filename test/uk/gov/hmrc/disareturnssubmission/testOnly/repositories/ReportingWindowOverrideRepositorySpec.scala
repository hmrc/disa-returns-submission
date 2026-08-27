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

import base.SpecBase
import org.bson.BsonType
import org.mongodb.scala.bson.collection.immutable.Document
import org.mongodb.scala.documentToUntypedDocument
import org.mongodb.scala.model.Filters
import org.mongodb.scala.{ObservableFuture, SingleObservableFuture}
import uk.gov.hmrc.disareturnssubmission.config.AppConfig
import uk.gov.hmrc.disareturnssubmission.testOnly.models.{ReportingWindowOverride, ReportingWindowOverrideRequest}
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import java.time.{Clock, Instant, ZoneOffset}

class ReportingWindowOverrideRepositorySpec
    extends SpecBase
    with DefaultPlayMongoRepositorySupport[ReportingWindowOverride] {

  override protected def databaseName: String = "disa-returns-submission-window-override-test"

  private val now       = Instant.parse("2026-08-25T12:00:00Z")
  private val clock     = Clock.fixed(now, ZoneOffset.UTC)
  private val appConfig = inject[AppConfig]

  override protected val repository: ReportingWindowOverrideRepository =
    new ReportingWindowOverrideRepository(mongoComponent, appConfig, clock)

  private lazy val rawCollection = mongoComponent.database.getCollection[Document]("reportingWindowOverrides")

  override protected def afterAll(): Unit =
    try dropDatabase()
    finally super.afterAll()

  "ReportingWindowOverrideRepository" - {

    "must configure an absolute TTL index" in {
      repository.ensureIndexes().futureValue

      val indexes = repository.collection.listIndexes().toFuture().futureValue
      val ttl     = indexes.find(_.getString("name") == "expiresAtTtlIdx").value

      ttl.get("key").value.asDocument().getInt32("expiresAt").getValue mustBe 1
      ttl.get("expireAfterSeconds").value.asNumber().longValue mustBe 0
    }

    "must upsert and isolate overrides by Z-reference" in {
      repository
        .set(testZReference, ReportingWindowOverrideRequest(now.minusSeconds(60), now.plusSeconds(60)))
        .futureValue
      repository
        .set("Z5678", ReportingWindowOverrideRequest(now, now.plusSeconds(120)))
        .futureValue

      repository.getActive(testZReference).futureValue.value.endDate mustBe now.plusSeconds(60)
      repository.getActive("Z5678").futureValue.value.endDate mustBe now.plusSeconds(120)
    }

    "must store expiry timestamps as BSON dates" in {
      repository.set(testZReference, ReportingWindowOverrideRequest(now, now.plusSeconds(60))).futureValue

      val document = rawCollection.find(Filters.equal("_id", testZReference)).first().toFuture().futureValue

      document.toBsonDocument.get("expiresAt").getBsonType mustBe BsonType.DATE_TIME
      document.toBsonDocument.get("updatedAt").getBsonType mustBe BsonType.DATE_TIME
    }

    "must delete only the specified override" in {
      repository.set(testZReference, ReportingWindowOverrideRequest(now, now.plusSeconds(60))).futureValue
      repository.set("Z5678", ReportingWindowOverrideRequest(now, now.plusSeconds(60))).futureValue

      repository.delete(testZReference).futureValue

      repository.getActive(testZReference).futureValue mustBe None
      repository.getActive("Z5678").futureValue must not be empty
    }
  }
}
