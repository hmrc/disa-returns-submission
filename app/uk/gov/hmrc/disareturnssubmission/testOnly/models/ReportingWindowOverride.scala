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

package uk.gov.hmrc.disareturnssubmission.testOnly.models

import play.api.libs.json.{Format, Json, JsonValidationError, OFormat, Reads}
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats

import java.time.Instant

final case class ReportingWindowOverrideRequest(startDate: Instant, endDate: Instant)

object ReportingWindowOverrideRequest {
  private val reads: Reads[ReportingWindowOverrideRequest] = Json
    .format[ReportingWindowOverrideRequest]
    .filter(JsonValidationError("startDate must be before or equal to endDate")) { request =>
      !request.startDate.isAfter(request.endDate)
    }

  implicit val format: OFormat[ReportingWindowOverrideRequest] =
    OFormat(reads, Json.writes[ReportingWindowOverrideRequest])
}

final case class ReportingWindowOverride(
  _id: String,
  startDate: Instant,
  endDate: Instant,
  expiresAt: Instant,
  updatedAt: Instant
)

object ReportingWindowOverride {
  implicit val instantFormat: Format[Instant]           = MongoJavatimeFormats.instantFormat
  implicit val format: OFormat[ReportingWindowOverride] = Json.format[ReportingWindowOverride]
}
