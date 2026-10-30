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
import play.api.test.Helpers.*
import uk.gov.hmrc.disareturnssubmission.config.AppConfig
import uk.gov.hmrc.disareturnssubmission.models.monthly.*
import uk.gov.hmrc.disareturnssubmission.repositories.*
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import java.time.*
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

    "deleteByZReferences" - {

      "must delete only monthly returns for the supplied Z-references" in {
        val otherZReference    = "Z5678"
        val retainedZReference = "Z9012"
        insertMonthlyReturn(buildMonthlyReturn())
        insertMonthlyReturn(buildMonthlyReturn().copy(zReference = otherZReference))
        insertMonthlyReturn(buildMonthlyReturn().copy(zReference = retainedZReference))

        await(repository.deleteByZReferences(Seq(zReference, otherZReference))) mustBe 2L

        await(repository.get(zReference, taxYear, month)) mustBe None
        await(repository.get(otherZReference, taxYear, month)) mustBe None
        await(repository.get(retainedZReference, taxYear, month)) must not be empty
      }
    }

    "markSubmissionNpsSubmitted" - {
      "must update only the matching stored submission and preserve its details" in {
        val original = buildMonthlyReturn(submissions = List(storedSubmission(), storedSubmission("other")))
        insertMonthlyReturn(original)

        await(repository.markSubmissionNpsSubmitted(zReference, taxYear, month, uploadReference)) mustBe true
        val updated = await(repository.get(zReference, taxYear, month)).value
        updated.submissions.head mustBe original.submissions.head
          .copy(status = MonthlyReturnSubmissionStatus.NpsSubmitted)
        updated.submissions(1) mustBe original.submissions(1)
        updated.lastUpdated mustBe fixedNow
        await(repository.markSubmissionNpsSubmitted(zReference, taxYear, month, uploadReference)) mustBe true
      }

      "must not update a created submission or match another submission's status" in {
        insertMonthlyReturn(buildMonthlyReturn(submissions = List(createdSubmission(), storedSubmission("other"))))

        await(repository.markSubmissionNpsSubmitted(zReference, taxYear, month, uploadReference)) mustBe false
        await(repository.markSubmissionNpsSubmitted(zReference, taxYear, month, "missing")) mustBe false
        await(repository.markSubmissionNpsSubmitted("Z9999", taxYear, month, "other")) mustBe false
      }
    }

    "get" - {

      "must return None when a MonthlyReturn does not exist" in {
        await(repository.get(zReference, taxYear, month)) mustBe None
      }

      "must return a MonthlyReturn by zReference, taxYear and month" in {
        val monthlyReturn = buildMonthlyReturn()

        insertMonthlyReturn(monthlyReturn)

        await(repository.get(zReference, taxYear, month)).value mustBe monthlyReturn
      }

      "must not return a MonthlyReturn with a different key" in {
        insertMonthlyReturn(buildMonthlyReturn())

        await(repository.get(zReference, taxYear, 6)) mustBe None
      }
    }

    "create" - {

      "must create a MonthlyReturn with nilReturn set to false" in {
        await(repository.create(zReference, taxYear, month, testSubmissionId, nilReturn = false)) mustBe Some(
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

        await(repository.get(zReference, taxYear, month)).value mustBe MonthlyReturn(
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
        await(repository.create(zReference, taxYear, month, testSubmissionId, nilReturn = true)) mustBe Some(
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

        await(repository.get(zReference, taxYear, month)).value mustBe MonthlyReturn(
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
        await(
          repository
            .create(zReference, taxYear, month, testSubmissionId, nilReturn = true)
        ).value.nilReturn mustBe true

        await(repository.create(zReference, taxYear, month, testSubmissionId, nilReturn = false)) mustBe None

        await(repository.get(zReference, taxYear, month)).value.nilReturn mustBe true
      }
    }

    "declare" - {

      "must declare a MonthlyReturn when it exists" in {
        insertMonthlyReturn(buildMonthlyReturn())

        val result = await(repository.declare(zReference, taxYear, month))

        val stored = await(repository.get(zReference, taxYear, month)).value
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

        await(
          repository
            .declare(
              zReference,
              taxYear,
              month,
              List(storedReference, pendingReference, pendingReference)
            )
        ) mustBe DeclareMonthlyReturnRepositoryResult.MonthlyReturnDeclared

        val stored = await(repository.get(zReference, taxYear, month)).value
        stored.submissions must contain theSameElementsInOrderAs List(
          storedSubmission(storedReference),
          MonthlyReturnSubmission(
            reference = pendingReference,
            status = MonthlyReturnSubmissionStatus.Created,
            createdOn = fixedNow
          )
        )
        stored.declaredOn mustBe Some(fixedNow)
      }

      "must return MonthlyReturnAlreadyDeclared when the MonthlyReturn has already been declared" in {
        insertMonthlyReturn(buildMonthlyReturn(declaredOn = Some(existingUpdated)))

        await(repository.declare(zReference, taxYear, month)) mustBe
          DeclareMonthlyReturnRepositoryResult.MonthlyReturnAlreadyDeclared

        await(repository.get(zReference, taxYear, month)).value.declaredOn mustBe Some(existingUpdated)
      }

      "must return MonthlyReturnNotFound when the MonthlyReturn does not exist" in {
        await(repository.declare(zReference, taxYear, month)) mustBe
          DeclareMonthlyReturnRepositoryResult.MonthlyReturnNotFound
      }

      "must only allow one concurrent declaration" in {
        insertMonthlyReturn(buildMonthlyReturn())

        val results = await(
          Future
            .sequence(
              Seq(
                repository.declare(zReference, taxYear, month),
                repository.declare(zReference, taxYear, month)
              )
            )
        )

        results.count(_ == DeclareMonthlyReturnRepositoryResult.MonthlyReturnDeclared) mustBe 1
        results.count(_ == DeclareMonthlyReturnRepositoryResult.MonthlyReturnAlreadyDeclared) mustBe 1
      }
    }

    "declareNilReturn" - {

      "must declare a MonthlyReturn as a nil return and clear submissions atomically" in {
        insertMonthlyReturn(buildMonthlyReturn(submissions = List(storedSubmission())))

        await(repository.declareNilReturn(zReference, taxYear, month)) mustBe
          DeclareMonthlyReturnRepositoryResult.MonthlyReturnDeclared

        val stored = await(repository.get(zReference, taxYear, month)).value
        stored.nilReturn mustBe true
        stored.submissions mustBe Nil
        stored.declaredOn mustBe Some(fixedNow)
        stored.lastUpdated mustBe fixedNow
      }

      "must return MonthlyReturnAlreadyDeclared when the MonthlyReturn has already been declared" in {
        insertMonthlyReturn(buildMonthlyReturn(declaredOn = Some(existingUpdated)))

        await(repository.declareNilReturn(zReference, taxYear, month)) mustBe
          DeclareMonthlyReturnRepositoryResult.MonthlyReturnAlreadyDeclared
      }

      "must return MonthlyReturnNotFound when the MonthlyReturn does not exist" in {
        await(repository.declareNilReturn(zReference, taxYear, month)) mustBe
          DeclareMonthlyReturnRepositoryResult.MonthlyReturnNotFound
      }
    }

    "storeSubmission" - {

      "must append a missing submission with STORED status without replacing existing submissions" in {
        val existingReference = "existing-reference"
        insertMonthlyReturn(buildMonthlyReturn(submissions = List(storedSubmission(existingReference))))

        await(
          repository
            .storeSubmission(zReference, taxYear, month, uploadReference, fileUploadDetails)
        ) mustBe StoreSubmissionRepositoryResult.SubmissionStored

        val stored = await(repository.get(zReference, taxYear, month)).value
        stored.submissions.map(_.reference) must contain theSameElementsInOrderAs List(
          existingReference,
          uploadReference
        )
        stored.submissions.last.status mustBe MonthlyReturnSubmissionStatus.Stored
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

        await(
          repository
            .storeSubmission(zReference, taxYear, month, uploadReference, fileUploadDetails)
        ) mustBe StoreSubmissionRepositoryResult.SubmissionStored

        val submissions = await(repository.get(zReference, taxYear, month)).value.submissions
        submissions.size mustBe 1
        val submission  = submissions.head
        submission mustBe storedSubmission(uploadReference)
        submission.createdOn mustBe createdOn
      }

      "must return SubmissionConflict when the submission is already STORED" in {
        insertMonthlyReturn(buildMonthlyReturn(submissions = List(storedSubmission(uploadReference))))

        await(
          repository
            .storeSubmission(zReference, taxYear, month, uploadReference, fileUploadDetails)
        ) mustBe StoreSubmissionRepositoryResult.SubmissionConflict

        await(repository.get(zReference, taxYear, month)).value.submissions.size mustBe 1
      }

      "must return SubmissionConflict for a missing submission after declaration" in {
        insertMonthlyReturn(buildMonthlyReturn(declaredOn = Some(existingUpdated)))

        await(
          repository
            .storeSubmission(zReference, taxYear, month, uploadReference, fileUploadDetails)
        ) mustBe StoreSubmissionRepositoryResult.SubmissionConflict
      }

      "must return SubmissionConflict for a nil return" in {
        insertMonthlyReturn(buildMonthlyReturn(nilReturn = true))

        await(
          repository
            .storeSubmission(zReference, taxYear, month, uploadReference, fileUploadDetails)
        ) mustBe StoreSubmissionRepositoryResult.SubmissionConflict
      }

      "must return MonthlyReturnNotFound when the return does not exist" in {
        await(
          repository
            .storeSubmission(zReference, taxYear, month, uploadReference, fileUploadDetails)
        ) mustBe StoreSubmissionRepositoryResult.MonthlyReturnNotFound
      }

      "must preserve concurrent submission creates for different references" in {
        val firstReference  = "11111111-1111-4111-8111-111111111111"
        val secondReference = "22222222-2222-4222-8222-222222222222"
        insertMonthlyReturn(buildMonthlyReturn())

        await(
          Future
            .sequence(
              Seq(
                repository.storeSubmission(zReference, taxYear, month, firstReference, fileUploadDetails),
                repository.storeSubmission(zReference, taxYear, month, secondReference, fileUploadDetails)
              )
            )
        ) must contain theSameElementsAs Seq(
          StoreSubmissionRepositoryResult.SubmissionStored,
          StoreSubmissionRepositoryResult.SubmissionStored
        )

        val stored = await(repository.get(zReference, taxYear, month)).value
        stored.submissions.map(_.reference) must contain theSameElementsAs Seq(firstReference, secondReference)
      }

      "must only store one concurrent submission for the same reference" in {
        insertMonthlyReturn(buildMonthlyReturn())

        val results = await(
          Future
            .sequence(
              Seq(
                repository.storeSubmission(zReference, taxYear, month, uploadReference, fileUploadDetails),
                repository.storeSubmission(zReference, taxYear, month, uploadReference, fileUploadDetails)
              )
            )
        )

        results.count(_ == StoreSubmissionRepositoryResult.SubmissionStored) mustBe 1
        results.count(_ == StoreSubmissionRepositoryResult.SubmissionConflict) mustBe 1
        await(repository.get(zReference, taxYear, month)).value.submissions.size mustBe 1
      }

      "must store a submission concurrently registered as pending by declaration" in {
        insertMonthlyReturn(buildMonthlyReturn())

        val declaration =
          repository.declare(zReference, taxYear, month, pendingSubmissionIds = List(uploadReference))
        val storage     =
          repository.storeSubmission(zReference, taxYear, month, uploadReference, fileUploadDetails)

        await(declaration) mustBe DeclareMonthlyReturnRepositoryResult.MonthlyReturnDeclared
        await(storage) mustBe StoreSubmissionRepositoryResult.SubmissionStored

        val stored = await(repository.get(zReference, taxYear, month)).value
        stored.declaredOn mustBe Some(fixedNow)
        stored.submissions.size mustBe 1
        stored.submissions.head.status mustBe MonthlyReturnSubmissionStatus.Stored
      }
    }

  }

  private def insertMonthlyReturn(monthlyReturn: MonthlyReturn): Unit =
    await(repository.collection.insertOne(monthlyReturn).toFuture())

  private def buildMonthlyReturn(
    nilReturn: Boolean = false,
    submissions: List[MonthlyReturnSubmission] = Nil,
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
  ): MonthlyReturnSubmission =
    MonthlyReturnSubmission(
      reference = reference,
      status = MonthlyReturnSubmissionStatus.Created,
      createdOn = createdOn
    )

  private def storedSubmission(
    reference: String = uploadReference
  ): MonthlyReturnSubmission =
    MonthlyReturnSubmission(
      reference = reference,
      status = MonthlyReturnSubmissionStatus.Stored,
      createdOn = createdOn,
      submissionDetails = Some(fileUploadDetails)
    )
}
