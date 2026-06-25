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

package base

import play.api.libs.Files as PlayFiles
import play.api.libs.Files.SingletonTemporaryFileCreator
import uk.gov.hmrc.disareturnssubmission.models.{MonthlyReturn, SubmissionDetails}
import uk.gov.hmrc.objectstore.client.Md5Hash

import java.time.Instant
import java.util.UUID

trait TestConstants {

  // ======= IT Tests =======
  protected val testServicePath                                = "/disa-returns-submission"
  protected val monthlyReturnsCollectionName                   = "monthlyReturns"
  protected val monthlyReturnFileUploadWorkItemsCollectionName = "monthlyReturnFileUploadWorkItems"

  // ====== Unit Tests ======
  protected val testZReference          = "Z1234"
  protected val lowercaseTestZReference = "z1234"
  protected val invalidTestZReference   = "1234"

  protected val testTaxYear                = "2026-27"
  protected val yearOnlyTestTaxYear        = "2026"
  protected val invalidTestTaxYear: String = yearOnlyTestTaxYear

  protected val testMonth        = 5
  protected val invalidTestMonth = 13

  protected val testSubmissionId: UUID = UUID.fromString("1d3df389-98d4-4fd1-b05d-88473fcba6ba")

  protected val testUploadReference = "2b4d6f3a-8c1e-4e4b-9c7a-123456789abc"

  protected val testExistingUpdatedOn: Instant   = Instant.parse("2026-05-17T11:00:00Z")
  protected val testRepositoryCreatedOn: Instant = Instant.parse("2026-05-17T11:30:00Z")
  protected val testCreatedOn: Instant           = Instant.parse("2026-05-17T12:00:00Z")
  protected val testUpscanCompletedOn: Instant   = Instant.parse("2026-05-17T12:01:00Z")

  protected val testExistingUpdatedOnString: String      = testExistingUpdatedOn.toString
  protected val testCreatedOnString: String              = testCreatedOn.toString
  protected val testExistingUpdatedOnEpochMillis: String = testExistingUpdatedOn.toEpochMilli.toString

  protected val zReferenceFieldName      = "zReference"
  protected val submissionIdFieldName    = "submissionId"
  protected val taxYearFieldName         = "taxYear"
  protected val monthFieldName           = "month"
  protected val nilReturnFieldName       = "nilReturn"
  protected val fileUploadsFieldName     = "fileUploads"
  protected val createdOnFieldName       = "createdOn"
  protected val declaredOnFieldName      = "declaredOn"
  protected val lastUpdatedFieldName     = "lastUpdated"
  protected val mongoDateFieldName       = "$date"
  protected val mongoNumberLongFieldName = "$numberLong"

  protected val testMongoDownMessage = "mongodb down"
  val testMd5Hash: Md5Hash           = Md5Hash("6QE/wgLIe+SOOzAt8Q78Sw==")

  protected val ndjsonContent: String =
    """{"id": "1", "name": "test"}
      |{"id": "2", "name": "test2"}
      |""".stripMargin

  protected val testTempFile: PlayFiles.TemporaryFile = {
    val file = SingletonTemporaryFileCreator.create("test", ".ndjson")
    java.nio.file.Files.write(file.path, ndjsonContent.getBytes)
    file
  }

  protected val monthlyReturn = MonthlyReturn(
    zReference = testZReference,
    submissionId = testSubmissionId,
    taxYear = testTaxYear,
    month = testMonth,
    createdOn = testExistingUpdatedOn,
    submissions = Nil,
    lastUpdated = testCreatedOn
  )

  protected val fileUploadDetails = SubmissionDetails(
    fileName = testUploadReference,
    fileMimeType = "application/x-ndjson",
    checksum = testMd5Hash.toString,
    size = 100L,
    objectStoreFileLocation = Some(testTempFile.toString)
  )
}
