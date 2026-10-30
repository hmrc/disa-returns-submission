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

import play.api.libs.json.*

import java.time.Instant

final case class MonthlyReturnSubmission(
  reference: String,
  status: MonthlyReturnSubmissionStatus,
  createdOn: Instant,
  submissionDetails: Option[MonthlyReturnSubmissionDetails] = None
)

object MonthlyReturnSubmission {
  implicit val format: OFormat[MonthlyReturnSubmission] = Json.format[MonthlyReturnSubmission]

  val mongoFormat: OFormat[MonthlyReturnSubmission] = {
    import MonthlyReturnFormats.mongoInstantFormat

    implicit val submissionDetailsFormat: OFormat[MonthlyReturnSubmissionDetails] =
      MonthlyReturnSubmissionDetails.mongoFormat

    Json.format[MonthlyReturnSubmission]
  }
}
