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

import play.api.libs.json.{Format, JsError, JsString, JsSuccess, Json, JsonValidationError, OFormat, OWrites, Reads, Writes}
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats

import java.time.{Instant, LocalDate}
import scala.util.Try

final case class ClockOverride(date: LocalDate)

object ClockOverride {
  implicit val localDateFormat: Format[LocalDate] = Format(
    Reads {
      case JsString(value) => Try(LocalDate.parse(value)).fold(_ => JsError("invalid date"), JsSuccess(_))
      case _               => JsError("date must be a string")
    },
    Writes(date => JsString(date.toString))
  )
  implicit val format: OFormat[ClockOverride] = Json.format[ClockOverride]
}

final case class ReportingWindowOverride(startDate: Instant, endDate: Instant)

object ReportingWindowOverride {
  implicit val instantFormat: Format[Instant] = Format(
    Reads {
      case JsString(value) => Try(Instant.parse(value)).fold(_ => JsError("invalid instant"), JsSuccess(_))
      case _               => JsError("instant must be a string")
    },
    Writes(instant => JsString(instant.toString))
  )
  implicit val format: OFormat[ReportingWindowOverride] = Json.format[ReportingWindowOverride]
}

final case class TestOverrideRequest(
  clock: Option[ClockOverride],
  reportingWindow: Option[ReportingWindowOverride]
)

object TestOverrideRequest {
  private val reads: Reads[TestOverrideRequest] = Reads { json =>
    for {
      clock           <- (json \ "clock").validateOpt[ClockOverride]
      reportingWindow <- (json \ "reportingWindow").validateOpt[ReportingWindowOverride]
    } yield TestOverrideRequest(clock, reportingWindow)
  }
    .filter(JsonValidationError("reportingWindow.startDate must be before or equal to reportingWindow.endDate")) {
      request =>
        request.reportingWindow.forall(window => !window.startDate.isAfter(window.endDate))
    }

  implicit val format: OFormat[TestOverrideRequest] =
    OFormat(reads, Json.writes[TestOverrideRequest])
}

final case class TestOverride(
  zReference: String,
  clock: Option[ClockOverride],
  reportingWindow: Option[ReportingWindowOverride]
)

object TestOverride {
  implicit val writes: OWrites[TestOverride] = OWrites { testOverride =>
    Json.obj(
      "zReference"      -> testOverride.zReference,
      "clock"           -> Json.toJson(testOverride.clock),
      "reportingWindow" -> Json.toJson(testOverride.reportingWindow)
    )
  }
}

final case class TestOverrideDocument(
  _id: String,
  clock: Option[ClockOverride],
  reportingWindow: Option[ReportingWindowOverride],
  expiresAt: Instant,
  updatedAt: Instant
)

object TestOverrideDocument {
  implicit val instantFormat: Format[Instant]                 = MongoJavatimeFormats.instantFormat
  implicit val format: OFormat[TestOverrideDocument]         = Json.format[TestOverrideDocument]
}
