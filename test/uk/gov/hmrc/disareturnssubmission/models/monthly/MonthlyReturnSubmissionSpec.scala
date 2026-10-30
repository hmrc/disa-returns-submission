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

package uk.gov.hmrc.disareturnssubmission.models.monthly

import base.SpecBase
import play.api.libs.json.*

class MonthlyReturnSubmissionSpec extends SpecBase {
  private val createdStatus   = "CREATED"
  private val storedStatus    = "STORED"
  private val submittedStatus = "NPS_SUBMITTED"

  "MonthlyReturnSubmission format" - {

    "must write CREATED and STORED statuses as uppercase strings" in {
      val created = MonthlyReturnSubmission(
        reference = testUploadReference,
        status = MonthlyReturnSubmissionStatus.Created,
        createdOn = testCreatedOn
      )
      val stored  = created.copy(status = MonthlyReturnSubmissionStatus.Stored)

      (Json.toJson(created) \ "status").as[JsString] mustBe JsString(createdStatus)
      (Json.toJson(stored) \ "status").as[JsString] mustBe JsString(storedStatus)
      (Json.toJson(created.copy(status = MonthlyReturnSubmissionStatus.NpsSubmitted)) \ "status")
        .as[JsString] mustBe JsString(
        submittedStatus
      )
    }

    "must read valid statuses" in {
      Json
        .fromJson[MonthlyReturnSubmissionStatus](JsString(createdStatus))
        .get mustBe MonthlyReturnSubmissionStatus.Created
      Json
        .fromJson[MonthlyReturnSubmissionStatus](JsString(storedStatus))
        .get mustBe MonthlyReturnSubmissionStatus.Stored
      Json
        .fromJson[MonthlyReturnSubmissionStatus](JsString(submittedStatus))
        .get mustBe MonthlyReturnSubmissionStatus.NpsSubmitted
    }

    "must reject an unknown status" in {
      Json.fromJson[MonthlyReturnSubmissionStatus](JsString("UNKNOWN")) mustBe a[JsError]
    }

    "must require status when reading a MonthlyReturnSubmission" in {
      val json = Json.obj(
        "reference" -> testUploadReference,
        "createdOn" -> testCreatedOn
      )

      Json.fromJson[MonthlyReturnSubmission](json) mustBe a[JsError]
    }
  }
}
