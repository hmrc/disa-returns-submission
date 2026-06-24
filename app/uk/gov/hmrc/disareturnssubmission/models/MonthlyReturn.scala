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
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats

import java.time.Instant
import java.util.UUID

private object MonthlyReturnFormats {
  implicit val mongoInstantFormat: Format[Instant] =
    Format(MongoJavatimeFormats.instantReads, MongoJavatimeFormats.instantWrites)

  def withCreatedOnDefault(json: JsValue): JsValue =
    json match {
      case jsonObject: JsObject if (jsonObject \ "createdOn").isEmpty =>
        (jsonObject \ "lastUpdated").toOption.fold(jsonObject)(lastUpdated => jsonObject + ("createdOn" -> lastUpdated))
      case _                                                          => json
    }
}

final case class MonthlyReturn(
  zReference: String,
  submissionId: UUID,
  taxYear: String,
  month: Int,
  createdOn: Instant,
  nilReturn: Boolean = false,
  submissions: List[Submission] = List(),
  declaredOn: Option[Instant] = None,
  lastUpdated: Instant
) {
  def hasDeclaration: Boolean = declaredOn.isDefined

  def declare(declaredOn: Instant): MonthlyReturn =
    if (hasDeclaration) {
      this
    } else {
      copy(
        declaredOn = Some(declaredOn),
        lastUpdated = declaredOn
      )
    }

  def createFileUpload(reference: String, createdOn: Instant, submissionDetails: SubmissionDetails): MonthlyReturn =
    if (nilReturn || submissions.exists(_.reference == reference)) {
      this
    } else {
      copy(
        submissions = submissions :+ Submission(
          reference = reference,
          createdOn = createdOn,
          submissionDetails = Some(submissionDetails)
        ),
        lastUpdated = createdOn
      )
    }

  def updateNilReturn(nilReturn: Boolean, updatedOn: Instant): MonthlyReturn =
    if (nilReturn) {
      val updatedReturn = copy(
        nilReturn = true,
        submissions = Nil,
        lastUpdated = updatedOn
      )

      if (updatedReturn == this) this else updatedReturn
    } else if (this.nilReturn) {
      copy(
        nilReturn = false,
        lastUpdated = updatedOn
      )
    } else {
      this
    }
}

object MonthlyReturn {
  import MonthlyReturnFormats.withCreatedOnDefault

  private val derivedFormat: OFormat[MonthlyReturn] =
    Json.using[Json.WithDefaultValues].format[MonthlyReturn]

  implicit val format: OFormat[MonthlyReturn] = OFormat(
    Reads(json => derivedFormat.reads(withCreatedOnDefault(json))),
    derivedFormat
  )

  val mongoFormat: OFormat[MonthlyReturn] = {
    import MonthlyReturnFormats.mongoInstantFormat

    implicit val submissionFormat: OFormat[Submission] = Submission.mongoFormat

    val derivedMongoFormat: OFormat[MonthlyReturn] =
      Json.using[Json.WithDefaultValues].format[MonthlyReturn]

    OFormat(
      Reads(json => derivedMongoFormat.reads(withCreatedOnDefault(json))),
      derivedMongoFormat
    )
  }
}

final case class Submission(
  reference: String,
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

final case class SubmissionDetails(
  fileName: String,
  fileMimeType: String,
  checksum: String,
  size: Long,
  objectStoreFileLocation: Option[String] = None,
  objectStoreFileErrorsLocation: Option[String] = None
)

object SubmissionDetails {
  implicit val format: OFormat[SubmissionDetails] = Json.format[SubmissionDetails]

  val mongoFormat: OFormat[SubmissionDetails] =
    Json.format[SubmissionDetails]
}
