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
import uk.gov.hmrc.disareturnssubmission.models.FileUploadStatus.*
import uk.gov.hmrc.disareturnssubmission.models.*
import uk.gov.hmrc.disareturnssubmission.repositories.{DeclareMonthlyReturnRepositoryResult, MonthlyReturnRepository, UpdateNilReturnRepositoryResult}
import uk.gov.hmrc.disareturnssubmission.repositories.MonthlyReturnRepository.CreateFileUploadRepositoryResult.*
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

  private val zReference        = testZReference
  private val taxYear           = yearOnlyTestTaxYear
  private val month             = testMonth
  private val uploadReference   = testUploadReference
  private val existingUpdated   = testExistingUpdatedOn
  private val createdOn         = testRepositoryCreatedOn
  private val fileUploadDetails = FileUploadDetails(
    fileName = testFileName,
    fileMimeType = testFileMimeType,
    uploadTimestamp = testCreatedOn,
    checksum = testChecksum,
    size = testFileSize,
    upscanDownloadUrl = testDownloadUrl
  )

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
            fileUploads = Nil,
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
          fileUploads = Nil,
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
            fileUploads = Nil,
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
          fileUploads = Nil,
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
          fileUploads = List(createdFileUpload(reference = "old-reference"))
        )
        val replacement = buildMonthlyReturn(
          fileUploads = List(createdFileUpload(reference = "new-reference"))
        )

        repository.upsert(existing).futureValue
        repository.upsert(replacement).futureValue mustBe true

        val stored = repository.get(zReference, taxYear, month).futureValue.value
        stored.fileUploads.map(_.reference) mustBe List("new-reference")
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

    "createFileUpload" - {

      "must return None when the MonthlyReturn does not exist" in {
        repository
          .createFileUpload(zReference, taxYear, month, uploadReference)
          .futureValue mustBe MonthlyReturnNotFound

        repository.get(zReference, taxYear, month).futureValue mustBe None
      }

      "must append a CREATED file upload when the MonthlyReturn exists" in {
        repository
          .upsert(buildMonthlyReturn(fileUploads = List(createdFileUpload(reference = "existing-reference"))))
          .futureValue

        val result = repository.createFileUpload(zReference, taxYear, month, uploadReference).futureValue

        val stored = repository.get(zReference, taxYear, month).futureValue.value
        result mustBe FileUploadCreated(stored)
        stored.fileUploads mustBe List(
          createdFileUpload(reference = "existing-reference"),
          FileUpload(
            reference = uploadReference,
            status = Created,
            createdOn = fixedNow
          )
        )
        stored.lastUpdated mustBe fixedNow
      }

      "must not duplicate an existing file upload reference" in {
        repository.upsert(buildMonthlyReturn()).futureValue
        repository.createFileUpload(zReference, taxYear, month, uploadReference).futureValue
        repository
          .createFileUpload(zReference, taxYear, month, uploadReference)
          .futureValue mustBe FileUploadAlreadyExists

        val stored = repository.get(zReference, taxYear, month).futureValue.value
        stored.fileUploads.map(_.reference) mustBe List(uploadReference)
      }

      "must not append a file upload when the MonthlyReturn is a nil return" in {
        repository.upsert(buildMonthlyReturn(nilReturn = true)).futureValue

        repository
          .createFileUpload(zReference, taxYear, month, uploadReference)
          .futureValue mustBe MonthlyReturnNotFound

        repository.get(zReference, taxYear, month).futureValue.value.fileUploads mustBe Nil
      }

    }

    "getFileUpload" - {

      "must return a FileUpload when the MonthlyReturn and FileUpload exist" in {
        val fileUpload = createdFileUpload()
        repository.upsert(buildMonthlyReturn(fileUploads = List(fileUpload))).futureValue

        repository.getFileUpload(zReference, taxYear, month, uploadReference).futureValue mustBe Some(fileUpload)
      }

      "must return None when the MonthlyReturn does not exist" in {
        repository.getFileUpload(zReference, taxYear, month, uploadReference).futureValue mustBe None
      }

      "must return None when the FileUpload does not exist" in {
        repository.upsert(buildMonthlyReturn()).futureValue

        repository.getFileUpload(zReference, taxYear, month, uploadReference).futureValue mustBe None
      }
    }

  }

  private def buildMonthlyReturn(
                                  nilReturn: Boolean = false,
                                  fileUploads: List[FileUpload] = Nil,
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
      fileUploads = fileUploads,
      declaredOn = declaredOn,
      lastUpdated = lastUpdated
    )

  private def createdFileUpload(
                                 reference: String = uploadReference
                               ): FileUpload =
    FileUpload(
      reference = reference,
      status = Created,
      createdOn = createdOn
    )
}