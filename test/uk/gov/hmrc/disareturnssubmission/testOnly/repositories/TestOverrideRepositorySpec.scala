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
import uk.gov.hmrc.disareturnssubmission.testOnly.models.*
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import java.time.{Clock, Instant, LocalDate, ZoneOffset}

class TestOverrideRepositorySpec extends SpecBase with DefaultPlayMongoRepositorySupport[TestOverrideDocument] {

  override protected def databaseName: String = "disa-returns-submission-test-override-test"

  private val now       = Instant.parse("2026-08-25T12:00:00Z")
  private val clock     = Clock.fixed(now, ZoneOffset.UTC)
  private val appConfig = inject[AppConfig]

  override protected val repository: TestOverrideRepository =
    new TestOverrideRepository(mongoComponent, appConfig, clock)

  private lazy val rawCollection = mongoComponent.database.getCollection[Document]("testOverrides")

  override protected def afterAll(): Unit =
    try dropDatabase()
    finally super.afterAll()

  "TestOverrideRepository" - {

    "must configure one absolute TTL index" in {
      repository.ensureIndexes().futureValue

      val indexes = repository.collection.listIndexes().toFuture().futureValue
      val ttl     = indexes.find(_.getString("name") == "expiresAtTtlIdx").value

      ttl.get("key").value.asDocument().getInt32("expiresAt").getValue mustBe 1
      ttl.get("expireAfterSeconds").value.asNumber().longValue mustBe 0
    }

    "must atomically replace the complete aggregate" in {
      val initial = TestOverrideRequest(
        Some(ClockOverride(LocalDate.parse("2026-06-17"))),
        Some(ReportingWindowOverride(now.minusSeconds(60), now.plusSeconds(60)))
      )
      repository.replace(testZReference, initial).futureValue

      repository.replace(testZReference, TestOverrideRequest(None, None)).futureValue

      val aggregate = repository.getActive(testZReference).futureValue.value
      aggregate.clock mustBe None
      aggregate.reportingWindow mustBe None
      rawCollection.countDocuments(Filters.equal("_id", testZReference)).toFuture().futureValue mustBe 1
    }

    "must isolate and delete aggregates by Z-reference" in {
      repository.replace(testZReference, TestOverrideRequest(None, None)).futureValue
      repository.replace("Z5678", TestOverrideRequest(None, None)).futureValue

      repository.delete(testZReference).futureValue

      repository.getActive(testZReference).futureValue mustBe None
      repository.getActive("Z5678").futureValue must not be empty
    }

    "must stop returning an expired aggregate before MongoDB removes it" in {
      repository.replace(testZReference, TestOverrideRequest(None, None)).futureValue
      val laterRepository = new TestOverrideRepository(
        mongoComponent,
        appConfig,
        Clock.fixed(now.plusSeconds(7200), ZoneOffset.UTC)
      )

      laterRepository.getActive(testZReference).futureValue mustBe None
    }

    "must store expiry timestamps as BSON dates" in {
      repository.replace(testZReference, TestOverrideRequest(None, None)).futureValue

      val document = rawCollection.find(Filters.equal("_id", testZReference)).first().toFuture().futureValue

      document.toBsonDocument.get("expiresAt").getBsonType mustBe BsonType.DATE_TIME
      document.toBsonDocument.get("updatedAt").getBsonType mustBe BsonType.DATE_TIME
    }
  }
}
