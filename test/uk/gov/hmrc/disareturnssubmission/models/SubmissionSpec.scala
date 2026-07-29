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
import play.api.libs.json.{JsError, JsString, Json}

class SubmissionSpec extends SpecBase {

  "Submission format" - {

    "must write CREATED and STORED statuses as uppercase strings" in {
      val created = Submission(
        reference = testUploadReference,
        status = SubmissionStatus.Created,
        createdOn = testCreatedOn
      )
      val stored  = created.copy(status = SubmissionStatus.Stored)

      (Json.toJson(created) \ "status").as[JsString] mustBe JsString("CREATED")
      (Json.toJson(stored) \ "status").as[JsString] mustBe JsString("STORED")
    }

    "must read valid statuses" in {
      Json.fromJson[SubmissionStatus](JsString("CREATED")).get mustBe SubmissionStatus.Created
      Json.fromJson[SubmissionStatus](JsString("STORED")).get mustBe SubmissionStatus.Stored
    }

    "must reject an unknown status" in {
      Json.fromJson[SubmissionStatus](JsString("UNKNOWN")) mustBe a[JsError]
    }

    "must require status when reading a Submission" in {
      val json = Json.obj(
        "reference" -> testUploadReference,
        "createdOn" -> testCreatedOn
      )

      Json.fromJson[Submission](json) mustBe a[JsError]
    }
  }
}
