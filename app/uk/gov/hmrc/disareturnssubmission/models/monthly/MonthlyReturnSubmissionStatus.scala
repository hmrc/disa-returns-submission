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

sealed trait MonthlyReturnSubmissionStatus {
  val value: String
}

object MonthlyReturnSubmissionStatus {

  case object Created extends MonthlyReturnSubmissionStatus {
    override val value: String = "CREATED"
  }

  case object Stored extends MonthlyReturnSubmissionStatus {
    override val value: String = "STORED"
  }

  case object NpsSubmitted extends MonthlyReturnSubmissionStatus {
    override val value: String = "NPS_SUBMITTED"
  }

  val values: Seq[MonthlyReturnSubmissionStatus] =
    Seq(Created, Stored, NpsSubmitted)

  private def fromString(value: String): Option[MonthlyReturnSubmissionStatus] =
    values.find(_.value == value)

  implicit val reads: Reads[MonthlyReturnSubmissionStatus] = Reads {
    case JsString(value) =>
      fromString(value)
        .map(JsSuccess(_))
        .getOrElse(JsError(s"Invalid submission status: $value"))
    case _               =>
      JsError("Submission status must be a string")
  }

  implicit val writes: Writes[MonthlyReturnSubmissionStatus] =
    Writes(status => JsString(status.value))

  implicit val format: Format[MonthlyReturnSubmissionStatus] =
    Format(reads, writes)
}
