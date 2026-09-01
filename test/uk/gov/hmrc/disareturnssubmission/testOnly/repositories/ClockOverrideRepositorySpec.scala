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
import uk.gov.hmrc.disareturnssubmission.config.AppConfig
import uk.gov.hmrc.disareturnssubmission.testOnly.models.ClockOverride
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import java.time.{Clock, Instant, ZoneOffset}

class ClockOverrideRepositorySpec extends SpecBase with DefaultPlayMongoRepositorySupport[ClockOverride] {

  override protected def databaseName: String = "disa-returns-submission-clock-override-test"

  private val now       = Instant.parse("2026-08-25T12:00:00Z")
  private val clock     = Clock.fixed(now, ZoneOffset.UTC)
  private val appConfig = inject[AppConfig]

  override protected val repository: ClockOverrideRepository =
    new ClockOverrideRepository(mongoComponent, appConfig, clock)

  override protected def afterAll(): Unit =
    try dropDatabase()
    finally super.afterAll()

  "ClockOverrideRepository" - {

    "must make an override available to another repository instance" in {
      val otherInstance = new ClockOverrideRepository(mongoComponent, appConfig, clock)
      val overrideTime  = Instant.parse("2026-09-17T00:00:00Z")

      repository.set(testZReference, overrideTime).futureValue

      otherInstance.getActive(testZReference).futureValue.value.instant mustBe overrideTime
    }

    "must isolate overrides by Z-reference" in {
      repository.set(testZReference, now).futureValue

      repository.getActive(testZReference).futureValue must not be empty
      repository.getActive("Z5678").futureValue mustBe None
    }

    "must delete only the specified override" in {
      repository.set(testZReference, now).futureValue
      repository.set("Z5678", now).futureValue

      repository.delete(testZReference).futureValue

      repository.getActive(testZReference).futureValue mustBe None
      repository.getActive("Z5678").futureValue must not be empty
    }
  }
}
