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

package uk.gov.hmrc.disareturnssubmission.models

import base.SpecBase
import play.api.libs.json.{JsObject, JsString, Json}

class MonthlyReturnSpec extends SpecBase {

  private val emptyMonthlyReturn = MonthlyReturn(
    zReference = testZReference,
    submissionId = testSubmissionId,
    taxYear = yearOnlyTestTaxYear,
    month = testMonth,
    createdOn = testExistingUpdatedOn,
    submissions = Nil,
    lastUpdated = testExistingUpdatedOn
  )

  "MonthlyReturn format" - {

    "must write instants as ISO strings for API JSON" in {
      val json = Json.toJson(emptyMonthlyReturn.copy(declaredOn = Some(testCreatedOn)))

      (json \ createdOnFieldName).as[JsString] mustBe JsString(testExistingUpdatedOnString)
      (json \ declaredOnFieldName).as[JsString] mustBe JsString(testCreatedOnString)
      (json \ lastUpdatedFieldName).as[JsString] mustBe JsString(testExistingUpdatedOnString)
    }

    "must write instants as Mongo date objects for Mongo JSON" in {
      val json = Json.toJson(emptyMonthlyReturn.copy(declaredOn = Some(testCreatedOn)))(MonthlyReturn.mongoFormat)

      ((json \ createdOnFieldName) \ mongoDateFieldName \ mongoNumberLongFieldName).as[JsString] mustBe
        JsString(testExistingUpdatedOnEpochMillis)
      ((json \ declaredOnFieldName) \ mongoDateFieldName \ mongoNumberLongFieldName).as[JsString] mustBe
        JsString(testCreatedOn.toEpochMilli.toString)
      ((json \ lastUpdatedFieldName) \ mongoDateFieldName \ mongoNumberLongFieldName).as[JsString] mustBe
        JsString(testExistingUpdatedOnEpochMillis)
    }

    "must default nilReturn to false when reading existing JSON" in {
      val jsonWithoutNilReturn = Json.toJson(emptyMonthlyReturn).as[JsObject] - nilReturnFieldName

      jsonWithoutNilReturn.as[MonthlyReturn] mustBe emptyMonthlyReturn
    }

    "must default createdOn to lastUpdated when reading existing JSON" in {
      val jsonWithoutCreatedOn = Json.toJson(emptyMonthlyReturn).as[JsObject] - createdOnFieldName

      jsonWithoutCreatedOn.as[MonthlyReturn] mustBe emptyMonthlyReturn
    }

    "must default createdOn to lastUpdated when reading existing Mongo JSON" in {
      val jsonWithoutCreatedOn =
        Json.toJson(emptyMonthlyReturn)(MonthlyReturn.mongoFormat).as[JsObject] - createdOnFieldName

      jsonWithoutCreatedOn.as[MonthlyReturn](MonthlyReturn.mongoFormat) mustBe emptyMonthlyReturn
    }
  }

  "declare" - {

    "must report whether the return has a declaration" in {
      emptyMonthlyReturn.hasDeclaration mustBe false
      emptyMonthlyReturn.copy(declaredOn = Some(testCreatedOn)).hasDeclaration mustBe true
    }

    "must set declaredOn and update lastUpdated" in {
      val result = emptyMonthlyReturn.declare(testCreatedOn)

      result.declaredOn mustBe Some(testCreatedOn)
      result.createdOn mustBe testExistingUpdatedOn
      result.lastUpdated mustBe testCreatedOn
    }

    "must leave an already declared return unchanged" in {
      val declaredMonthlyReturn = emptyMonthlyReturn.copy(declaredOn = Some(testExistingUpdatedOn))

      declaredMonthlyReturn.declare(testCreatedOn) mustBe declaredMonthlyReturn
    }
  }

  "hasStoredSubmission" - {

    "must report whether the reference belongs to a STORED submission" in {
      val monthlyReturn = emptyMonthlyReturn.copy(
        submissions = List(
          Submission(
            reference = testUploadReference,
            status = SubmissionStatus.Created,
            createdOn = testCreatedOn
          ),
          Submission(
            reference = "stored-reference",
            status = SubmissionStatus.Stored,
            createdOn = testCreatedOn,
            submissionDetails = Some(fileUploadDetails)
          )
        )
      )

      monthlyReturn.hasStoredSubmission(testUploadReference) mustBe false
      monthlyReturn.hasStoredSubmission("stored-reference") mustBe true
      monthlyReturn.hasStoredSubmission("missing-reference") mustBe false
    }
  }

  "canStoreSubmission" - {

    "must allow a missing submission before declaration" in {
      emptyMonthlyReturn.canStoreSubmission(testUploadReference) mustBe true
    }

    "must allow a CREATED submission after declaration" in {
      val monthlyReturn = emptyMonthlyReturn.copy(
        submissions = List(
          Submission(
            reference = testUploadReference,
            status = SubmissionStatus.Created,
            createdOn = testCreatedOn
          )
        ),
        declaredOn = Some(testCreatedOn)
      )

      monthlyReturn.canStoreSubmission(testUploadReference) mustBe true
    }

    "must not allow a STORED submission" in {
      val monthlyReturn = emptyMonthlyReturn.storeSubmission(testUploadReference, testCreatedOn, fileUploadDetails)

      monthlyReturn.canStoreSubmission(testUploadReference) mustBe false
    }

    "must not allow a missing submission after declaration" in {
      val monthlyReturn = emptyMonthlyReturn.copy(declaredOn = Some(testCreatedOn))

      monthlyReturn.canStoreSubmission(testUploadReference) mustBe false
    }

    "must not allow a missing submission for a nil return" in {
      val monthlyReturn = emptyMonthlyReturn.copy(nilReturn = true)

      monthlyReturn.canStoreSubmission(testUploadReference) mustBe false
    }
  }

  "createPendingSubmissions" - {

    "must add distinct CREATED submissions and update lastUpdated" in {
      val secondReference = "second-reference"
      val result          =
        emptyMonthlyReturn.createPendingSubmissions(
          List(testUploadReference, secondReference, testUploadReference),
          testCreatedOn
        )

      result.submissions mustBe List(
        Submission(
          reference = testUploadReference,
          status = SubmissionStatus.Created,
          createdOn = testCreatedOn
        ),
        Submission(
          reference = secondReference,
          status = SubmissionStatus.Created,
          createdOn = testCreatedOn
        )
      )
      result.createdOn mustBe testExistingUpdatedOn
      result.lastUpdated mustBe testCreatedOn
    }

    "must not replace an existing submission" in {
      val existing = emptyMonthlyReturn.storeSubmission(testUploadReference, testCreatedOn, fileUploadDetails)

      existing.createPendingSubmissions(List(testUploadReference), testUpscanCompletedOn) mustBe existing
    }

    "must not add pending submissions to a nil return" in {
      val monthlyReturn = emptyMonthlyReturn.copy(nilReturn = true)

      monthlyReturn.createPendingSubmissions(List(testUploadReference), testCreatedOn) mustBe monthlyReturn
    }
  }

  "storeSubmission" - {

    "must update a CREATED submission to STORED and preserve its createdOn" in {
      val pending = emptyMonthlyReturn.createPendingSubmissions(List(testUploadReference), testCreatedOn)

      val result = pending.storeSubmission(testUploadReference, testUpscanCompletedOn, fileUploadDetails)

      result.submissions mustBe List(
        Submission(
          reference = testUploadReference,
          status = SubmissionStatus.Stored,
          createdOn = testCreatedOn,
          submissionDetails = Some(fileUploadDetails)
        )
      )
      result.lastUpdated mustBe testUpscanCompletedOn
    }

    "must create a missing submission with STORED status before declaration" in {
      val result = emptyMonthlyReturn.storeSubmission(testUploadReference, testCreatedOn, fileUploadDetails)

      result.submissions mustBe List(
        Submission(
          reference = testUploadReference,
          status = SubmissionStatus.Stored,
          createdOn = testCreatedOn,
          submissionDetails = Some(fileUploadDetails)
        )
      )
    }

    "must not update a submission that is already STORED" in {
      val stored = emptyMonthlyReturn.storeSubmission(testUploadReference, testCreatedOn, fileUploadDetails)

      stored.storeSubmission(testUploadReference, testUpscanCompletedOn, fileUploadDetails) mustBe stored
    }

    "must not create a missing submission after declaration" in {
      val declared = emptyMonthlyReturn.copy(declaredOn = Some(testCreatedOn))

      declared.storeSubmission(testUploadReference, testUpscanCompletedOn, fileUploadDetails) mustBe declared
    }
  }

  "updateNilReturn" - {

    "must set nilReturn to true and remove all file uploads" in {
      val monthlyReturn = emptyMonthlyReturn.storeSubmission(testUploadReference, testCreatedOn, fileUploadDetails)

      val result = monthlyReturn.updateNilReturn(nilReturn = true, updatedOn = testUpscanCompletedOn)

      result.nilReturn mustBe true
      result.submissions mustBe Nil
      result.createdOn mustBe testExistingUpdatedOn
      result.lastUpdated mustBe testUpscanCompletedOn
    }

    "must set nilReturn to false and leave file uploads empty" in {
      val monthlyReturn = emptyMonthlyReturn.copy(nilReturn = true)

      val result = monthlyReturn.updateNilReturn(nilReturn = false, updatedOn = testUpscanCompletedOn)

      result.nilReturn mustBe false
      result.submissions mustBe Nil
      result.createdOn mustBe testExistingUpdatedOn
      result.lastUpdated mustBe testUpscanCompletedOn
    }

    "must leave a non-nil return unchanged when setting nilReturn to false" in {
      emptyMonthlyReturn.updateNilReturn(nilReturn = false, updatedOn = testUpscanCompletedOn) mustBe emptyMonthlyReturn
    }
  }
}
