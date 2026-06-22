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
    fileUploads = Nil,
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

  "createFileUpload" - {

    "must add a CREATED file upload and update lastUpdated" in {
      val result = emptyMonthlyReturn.createFileUpload(testUploadReference, testCreatedOn, fileUploadDetails)

      result.fileUploads mustBe List(
        FileUpload(
          reference = testUploadReference,
          createdOn = testCreatedOn,
          fileUploadDetails = Some(fileUploadDetails)
        )
      )
      result.createdOn mustBe testExistingUpdatedOn
      result.lastUpdated mustBe testCreatedOn
    }

    "must not add a duplicate upload reference" in {
      val existing = emptyMonthlyReturn.createFileUpload(testUploadReference, testCreatedOn, fileUploadDetails)

      existing.createFileUpload(testUploadReference, testUpscanCompletedOn, fileUploadDetails) mustBe existing
    }

    "must not add a file upload to a nil return" in {
      val monthlyReturn = emptyMonthlyReturn.copy(nilReturn = true)

      monthlyReturn.createFileUpload(testUploadReference, testCreatedOn, fileUploadDetails) mustBe monthlyReturn
    }
  }

  "updateNilReturn" - {

    "must set nilReturn to true and remove all file uploads" in {
      val monthlyReturn = emptyMonthlyReturn.createFileUpload(testUploadReference, testCreatedOn, fileUploadDetails)

      val result = monthlyReturn.updateNilReturn(nilReturn = true, updatedOn = testUpscanCompletedOn)

      result.nilReturn mustBe true
      result.fileUploads mustBe Nil
      result.createdOn mustBe testExistingUpdatedOn
      result.lastUpdated mustBe testUpscanCompletedOn
    }

    "must set nilReturn to false and leave file uploads empty" in {
      val monthlyReturn = emptyMonthlyReturn.copy(nilReturn = true)

      val result = monthlyReturn.updateNilReturn(nilReturn = false, updatedOn = testUpscanCompletedOn)

      result.nilReturn mustBe false
      result.fileUploads mustBe Nil
      result.createdOn mustBe testExistingUpdatedOn
      result.lastUpdated mustBe testUpscanCompletedOn
    }

    "must leave a non-nil return unchanged when setting nilReturn to false" in {
      emptyMonthlyReturn.updateNilReturn(nilReturn = false, updatedOn = testUpscanCompletedOn) mustBe emptyMonthlyReturn
    }
  }
}
