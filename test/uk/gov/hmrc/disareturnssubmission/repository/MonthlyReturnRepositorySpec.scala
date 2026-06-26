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

package uk.gov.hmrc.disareturnssubmission.repository

import base.SpecBase
import uk.gov.hmrc.disareturnssubmission.config.AppConfig
import uk.gov.hmrc.disareturnssubmission.models.*
import uk.gov.hmrc.disareturnssubmission.repositories.{DeclareMonthlyReturnRepositoryResult, MonthlyReturnRepository}
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import java.time.{Clock, Instant, ZoneOffset}

class MonthlyReturnRepositorySpec extends SpecBase with DefaultPlayMongoRepositorySupport[MonthlyReturn] {

  override protected def databaseName: String = "disa-returns-backend-monthly-return-repository-test"

  private val fixedNow: Instant = testCreatedOn
  private val fixedClock: Clock = Clock.fixed(fixedNow, ZoneOffset.UTC)

  private lazy val appConfig: AppConfig = inject[AppConfig]

  override protected val repository: MonthlyReturnRepository =
    new MonthlyReturnRepository(mongoComponent, appConfig, fixedClock)

  override protected def afterAll(): Unit =
    try dropDatabase()
    finally super.afterAll()

  private val zReference      = testZReference
  private val taxYear         = yearOnlyTestTaxYear
  private val month           = testMonth
  private val uploadReference = testUploadReference
  private val existingUpdated = testExistingUpdatedOn
  private val createdOn       = testRepositoryCreatedOn

  "MonthlyReturnRepository" - {

    "get" - {

      "must return None when a MonthlyReturn does not exist" in {
        repository.get(zReference, taxYear, month).futureValue mustBe None
      }

      "must return a MonthlyReturn by zReference, taxYear and month" in {
        val monthlyReturn = buildMonthlyReturn()

        repository.upsert(monthlyReturn).futureValue

        repository.get(zReference, taxYear, month).futureValue.value mustBe monthlyReturn.copy(lastUpdated = fixedNow)
      }

      "must not return a MonthlyReturn with a different key" in {
        repository.upsert(buildMonthlyReturn()).futureValue

        repository.get(zReference, taxYear, 6).futureValue mustBe None
      }
    }

    "create" - {

      "must create a MonthlyReturn with nilReturn set to false" in {
        repository.create(zReference, taxYear, month, testSubmissionId, nilReturn = false).futureValue mustBe Some(
          MonthlyReturn(
            zReference = zReference,
            submissionId = testSubmissionId,
            taxYear = taxYear,
            month = month,
            createdOn = fixedNow,
            nilReturn = false,
            submissions = Nil,
            lastUpdated = fixedNow
          )
        )

        repository.get(zReference, taxYear, month).futureValue.value mustBe MonthlyReturn(
          zReference = zReference,
          submissionId = testSubmissionId,
          taxYear = taxYear,
          month = month,
          createdOn = fixedNow,
          nilReturn = false,
          submissions = Nil,
          lastUpdated = fixedNow
        )
      }

      "must create a MonthlyReturn with nilReturn set to true and no file uploads" in {
        repository.create(zReference, taxYear, month, testSubmissionId, nilReturn = true).futureValue mustBe Some(
          MonthlyReturn(
            zReference = zReference,
            submissionId = testSubmissionId,
            taxYear = taxYear,
            month = month,
            createdOn = fixedNow,
            nilReturn = true,
            submissions = Nil,
            lastUpdated = fixedNow
          )
        )

        repository.get(zReference, taxYear, month).futureValue.value mustBe MonthlyReturn(
          zReference = zReference,
          submissionId = testSubmissionId,
          taxYear = taxYear,
          month = month,
          createdOn = fixedNow,
          nilReturn = true,
          submissions = Nil,
          lastUpdated = fixedNow
        )
      }

      "must return None when a MonthlyReturn already exists for the same key" in {
        repository
          .create(zReference, taxYear, month, testSubmissionId, nilReturn = true)
          .futureValue
          .value
          .nilReturn mustBe true

        repository.create(zReference, taxYear, month, testSubmissionId, nilReturn = false).futureValue mustBe None

        repository.get(zReference, taxYear, month).futureValue.value.nilReturn mustBe true
      }
    }

    "upsert" - {

      "must insert a MonthlyReturn and set lastUpdated" in {
        val monthlyReturn = buildMonthlyReturn(lastUpdated = existingUpdated)

        repository.upsert(monthlyReturn).futureValue mustBe true

        repository.get(zReference, taxYear, month).futureValue.value mustBe monthlyReturn.copy(lastUpdated = fixedNow)
      }

      "must replace an existing MonthlyReturn for the same key" in {
        val existing    = buildMonthlyReturn(
          submissions = List(createdFileUpload(reference = "old-reference"))
        )
        val replacement = buildMonthlyReturn(
          submissions = List(createdFileUpload(reference = "new-reference"))
        )

        repository.upsert(existing).futureValue
        repository.upsert(replacement).futureValue mustBe true

        val stored = repository.get(zReference, taxYear, month).futureValue.value
        stored.submissions.map(_.reference) mustBe List("new-reference")
        stored.lastUpdated mustBe fixedNow
      }
    }

    "declare" - {

      "must declare a MonthlyReturn when it exists" in {
        repository.upsert(buildMonthlyReturn()).futureValue

        val result = repository.declare(zReference, taxYear, month).futureValue

        val stored = repository.get(zReference, taxYear, month).futureValue.value
        result mustBe DeclareMonthlyReturnRepositoryResult.MonthlyReturnDeclared
        stored.declaredOn mustBe Some(fixedNow)
        stored.createdOn mustBe existingUpdated
        stored.lastUpdated mustBe fixedNow
      }

      "must return MonthlyReturnAlreadyDeclared when the MonthlyReturn has already been declared" in {
        repository.upsert(buildMonthlyReturn(declaredOn = Some(existingUpdated))).futureValue

        repository.declare(zReference, taxYear, month).futureValue mustBe
          DeclareMonthlyReturnRepositoryResult.MonthlyReturnAlreadyDeclared

        repository.get(zReference, taxYear, month).futureValue.value.declaredOn mustBe Some(existingUpdated)
      }

      "must return MonthlyReturnNotFound when the MonthlyReturn does not exist" in {
        repository.declare(zReference, taxYear, month).futureValue mustBe
          DeclareMonthlyReturnRepositoryResult.MonthlyReturnNotFound
      }
    }

  }

  private def buildMonthlyReturn(
    nilReturn: Boolean = false,
    submissions: List[Submission] = Nil,
    declaredOn: Option[Instant] = None,
    lastUpdated: Instant = existingUpdated
  ): MonthlyReturn =
    MonthlyReturn(
      zReference = zReference,
      submissionId = testSubmissionId,
      taxYear = taxYear,
      month = month,
      createdOn = lastUpdated,
      nilReturn = nilReturn,
      submissions = submissions,
      declaredOn = declaredOn,
      lastUpdated = lastUpdated
    )

  private def createdFileUpload(
    reference: String = uploadReference
  ): Submission =
    Submission(
      reference = reference,
      createdOn = createdOn
    )
}
