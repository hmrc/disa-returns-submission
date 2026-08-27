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
import org.mongodb.scala.SingleObservableFuture
import uk.gov.hmrc.disareturnssubmission.config.AppConfig
import uk.gov.hmrc.disareturnssubmission.models.*
import uk.gov.hmrc.disareturnssubmission.repositories.{DeclareMonthlyReturnRepositoryResult, MonthlyReturnRepository, StoreSubmissionRepositoryResult}
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import java.time.{Clock, Instant, ZoneOffset}
import scala.concurrent.Future

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

    "deleteByZReference" - {

      "must delete only monthly returns for the supplied Z-reference" in {
        val otherZReference = "Z5678"
        insertMonthlyReturn(buildMonthlyReturn())
        insertMonthlyReturn(buildMonthlyReturn().copy(zReference = otherZReference))

        repository.deleteByZReference(zReference).futureValue mustBe 1L

        repository.get(zReference, taxYear, month).futureValue mustBe None
        repository.get(otherZReference, taxYear, month).futureValue must not be empty
      }
    }

    "get" - {

      "must return None when a MonthlyReturn does not exist" in {
        repository.get(zReference, taxYear, month).futureValue mustBe None
      }

      "must return a MonthlyReturn by zReference, taxYear and month" in {
        val monthlyReturn = buildMonthlyReturn()

        insertMonthlyReturn(monthlyReturn)

        repository.get(zReference, taxYear, month).futureValue.value mustBe monthlyReturn
      }

      "must not return a MonthlyReturn with a different key" in {
        insertMonthlyReturn(buildMonthlyReturn())

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

    "declare" - {

      "must declare a MonthlyReturn when it exists" in {
        insertMonthlyReturn(buildMonthlyReturn())

        val result = repository.declare(zReference, taxYear, month).futureValue

        val stored = repository.get(zReference, taxYear, month).futureValue.value
        result mustBe DeclareMonthlyReturnRepositoryResult.MonthlyReturnDeclared
        stored.declaredOn mustBe Some(fixedNow)
        stored.createdOn mustBe existingUpdated
        stored.lastUpdated mustBe fixedNow
      }

      "must create missing pending submissions with CREATED status when declaring" in {
        val storedReference  = "stored-reference"
        val pendingReference = "$pending-reference"
        insertMonthlyReturn(
          buildMonthlyReturn(
            submissions = List(storedSubmission(storedReference))
          )
        )

        repository
          .declare(
            zReference,
            taxYear,
            month,
            List(storedReference, pendingReference, pendingReference)
          )
          .futureValue mustBe DeclareMonthlyReturnRepositoryResult.MonthlyReturnDeclared

        val stored = repository.get(zReference, taxYear, month).futureValue.value
        stored.submissions must contain theSameElementsInOrderAs List(
          storedSubmission(storedReference),
          Submission(
            reference = pendingReference,
            status = SubmissionStatus.Created,
            createdOn = fixedNow
          )
        )
        stored.declaredOn mustBe Some(fixedNow)
      }

      "must return MonthlyReturnAlreadyDeclared when the MonthlyReturn has already been declared" in {
        insertMonthlyReturn(buildMonthlyReturn(declaredOn = Some(existingUpdated)))

        repository.declare(zReference, taxYear, month).futureValue mustBe
          DeclareMonthlyReturnRepositoryResult.MonthlyReturnAlreadyDeclared

        repository.get(zReference, taxYear, month).futureValue.value.declaredOn mustBe Some(existingUpdated)
      }

      "must return MonthlyReturnNotFound when the MonthlyReturn does not exist" in {
        repository.declare(zReference, taxYear, month).futureValue mustBe
          DeclareMonthlyReturnRepositoryResult.MonthlyReturnNotFound
      }

      "must only allow one concurrent declaration" in {
        insertMonthlyReturn(buildMonthlyReturn())

        val results = Future
          .sequence(
            Seq(
              repository.declare(zReference, taxYear, month),
              repository.declare(zReference, taxYear, month)
            )
          )
          .futureValue

        results.count(_ == DeclareMonthlyReturnRepositoryResult.MonthlyReturnDeclared) mustBe 1
        results.count(_ == DeclareMonthlyReturnRepositoryResult.MonthlyReturnAlreadyDeclared) mustBe 1
      }
    }

    "declareNilReturn" - {

      "must declare a MonthlyReturn as a nil return and clear submissions atomically" in {
        insertMonthlyReturn(buildMonthlyReturn(submissions = List(storedSubmission())))

        repository.declareNilReturn(zReference, taxYear, month).futureValue mustBe
          DeclareMonthlyReturnRepositoryResult.MonthlyReturnDeclared

        val stored = repository.get(zReference, taxYear, month).futureValue.value
        stored.nilReturn mustBe true
        stored.submissions mustBe Nil
        stored.declaredOn mustBe Some(fixedNow)
        stored.lastUpdated mustBe fixedNow
      }

      "must return MonthlyReturnAlreadyDeclared when the MonthlyReturn has already been declared" in {
        insertMonthlyReturn(buildMonthlyReturn(declaredOn = Some(existingUpdated)))

        repository.declareNilReturn(zReference, taxYear, month).futureValue mustBe
          DeclareMonthlyReturnRepositoryResult.MonthlyReturnAlreadyDeclared
      }

      "must return MonthlyReturnNotFound when the MonthlyReturn does not exist" in {
        repository.declareNilReturn(zReference, taxYear, month).futureValue mustBe
          DeclareMonthlyReturnRepositoryResult.MonthlyReturnNotFound
      }
    }

    "storeSubmission" - {

      "must append a missing submission with STORED status without replacing existing submissions" in {
        val existingReference = "existing-reference"
        insertMonthlyReturn(buildMonthlyReturn(submissions = List(storedSubmission(existingReference))))

        repository
          .storeSubmission(zReference, taxYear, month, uploadReference, fileUploadDetails)
          .futureValue mustBe StoreSubmissionRepositoryResult.SubmissionStored

        val stored = repository.get(zReference, taxYear, month).futureValue.value
        stored.submissions.map(_.reference) must contain theSameElementsInOrderAs List(
          existingReference,
          uploadReference
        )
        stored.submissions.last.status mustBe SubmissionStatus.Stored
        stored.submissions.last.submissionDetails mustBe Some(fileUploadDetails)
        stored.lastUpdated mustBe fixedNow
      }

      "must update an existing CREATED submission to STORED after declaration" in {
        insertMonthlyReturn(
          buildMonthlyReturn(
            submissions = List(createdSubmission(uploadReference)),
            declaredOn = Some(existingUpdated)
          )
        )

        repository
          .storeSubmission(zReference, taxYear, month, uploadReference, fileUploadDetails)
          .futureValue mustBe StoreSubmissionRepositoryResult.SubmissionStored

        val submissions = repository.get(zReference, taxYear, month).futureValue.value.submissions
        submissions.size mustBe 1
        val submission  = submissions.head
        submission mustBe storedSubmission(uploadReference)
        submission.createdOn mustBe createdOn
      }

      "must return SubmissionConflict when the submission is already STORED" in {
        insertMonthlyReturn(buildMonthlyReturn(submissions = List(storedSubmission(uploadReference))))

        repository
          .storeSubmission(zReference, taxYear, month, uploadReference, fileUploadDetails)
          .futureValue mustBe StoreSubmissionRepositoryResult.SubmissionConflict

        repository.get(zReference, taxYear, month).futureValue.value.submissions.size mustBe 1
      }

      "must return SubmissionConflict for a missing submission after declaration" in {
        insertMonthlyReturn(buildMonthlyReturn(declaredOn = Some(existingUpdated)))

        repository
          .storeSubmission(zReference, taxYear, month, uploadReference, fileUploadDetails)
          .futureValue mustBe StoreSubmissionRepositoryResult.SubmissionConflict
      }

      "must return SubmissionConflict for a nil return" in {
        insertMonthlyReturn(buildMonthlyReturn(nilReturn = true))

        repository
          .storeSubmission(zReference, taxYear, month, uploadReference, fileUploadDetails)
          .futureValue mustBe StoreSubmissionRepositoryResult.SubmissionConflict
      }

      "must return MonthlyReturnNotFound when the return does not exist" in {
        repository
          .storeSubmission(zReference, taxYear, month, uploadReference, fileUploadDetails)
          .futureValue mustBe StoreSubmissionRepositoryResult.MonthlyReturnNotFound
      }

      "must preserve concurrent submission creates for different references" in {
        val firstReference  = "11111111-1111-4111-8111-111111111111"
        val secondReference = "22222222-2222-4222-8222-222222222222"
        insertMonthlyReturn(buildMonthlyReturn())

        Future
          .sequence(
            Seq(
              repository.storeSubmission(zReference, taxYear, month, firstReference, fileUploadDetails),
              repository.storeSubmission(zReference, taxYear, month, secondReference, fileUploadDetails)
            )
          )
          .futureValue must contain theSameElementsAs Seq(
          StoreSubmissionRepositoryResult.SubmissionStored,
          StoreSubmissionRepositoryResult.SubmissionStored
        )

        val stored = repository.get(zReference, taxYear, month).futureValue.value
        stored.submissions.map(_.reference) must contain theSameElementsAs Seq(firstReference, secondReference)
      }

      "must only store one concurrent submission for the same reference" in {
        insertMonthlyReturn(buildMonthlyReturn())

        val results = Future
          .sequence(
            Seq(
              repository.storeSubmission(zReference, taxYear, month, uploadReference, fileUploadDetails),
              repository.storeSubmission(zReference, taxYear, month, uploadReference, fileUploadDetails)
            )
          )
          .futureValue

        results.count(_ == StoreSubmissionRepositoryResult.SubmissionStored) mustBe 1
        results.count(_ == StoreSubmissionRepositoryResult.SubmissionConflict) mustBe 1
        repository.get(zReference, taxYear, month).futureValue.value.submissions.size mustBe 1
      }

      "must store a submission concurrently registered as pending by declaration" in {
        insertMonthlyReturn(buildMonthlyReturn())

        val declaration =
          repository.declare(zReference, taxYear, month, pendingSubmissionIds = List(uploadReference))
        val storage     =
          repository.storeSubmission(zReference, taxYear, month, uploadReference, fileUploadDetails)

        declaration.futureValue mustBe DeclareMonthlyReturnRepositoryResult.MonthlyReturnDeclared
        storage.futureValue mustBe StoreSubmissionRepositoryResult.SubmissionStored

        val stored = repository.get(zReference, taxYear, month).futureValue.value
        stored.declaredOn mustBe Some(fixedNow)
        stored.submissions.size mustBe 1
        stored.submissions.head.status mustBe SubmissionStatus.Stored
      }
    }

  }

  private def insertMonthlyReturn(monthlyReturn: MonthlyReturn): Unit =
    repository.collection.insertOne(monthlyReturn).toFuture().futureValue

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

  private def createdSubmission(
    reference: String = uploadReference
  ): Submission =
    Submission(
      reference = reference,
      status = SubmissionStatus.Created,
      createdOn = createdOn
    )

  private def storedSubmission(
    reference: String = uploadReference
  ): Submission =
    Submission(
      reference = reference,
      status = SubmissionStatus.Stored,
      createdOn = createdOn,
      submissionDetails = Some(fileUploadDetails)
    )
}
