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

import play.api.libs.json.{Json, OFormat}

import java.time.Instant

final case class Submission(
  reference: String,
  status: SubmissionStatus,
  createdOn: Instant,
  submissionDetails: Option[SubmissionDetails] = None
)

object Submission {
  implicit val format: OFormat[Submission] = Json.format[Submission]

  val mongoFormat: OFormat[Submission] = {
    import MonthlyReturnFormats.mongoInstantFormat

    implicit val submissionDetailsFormat: OFormat[SubmissionDetails] = SubmissionDetails.mongoFormat

    Json.format[Submission]
  }
}
