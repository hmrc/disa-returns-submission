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

import play.api.libs.json.*

sealed trait SubmissionStatus {
  val value: String
}

object SubmissionStatus {

  case object Created extends SubmissionStatus {
    override val value: String = "CREATED"
  }

  case object Stored extends SubmissionStatus {
    override val value: String = "STORED"
  }

  val values: Seq[SubmissionStatus] =
    Seq(Created, Stored)

  private def fromString(value: String): Option[SubmissionStatus] =
    values.find(_.value == value)

  implicit val reads: Reads[SubmissionStatus] = Reads {
    case JsString(value) =>
      fromString(value)
        .map(JsSuccess(_))
        .getOrElse(JsError(s"Invalid submission status: $value"))
    case _               =>
      JsError("Submission status must be a string")
  }

  implicit val writes: Writes[SubmissionStatus] =
    Writes(status => JsString(status.value))

  implicit val format: Format[SubmissionStatus] =
    Format(reads, writes)
}
