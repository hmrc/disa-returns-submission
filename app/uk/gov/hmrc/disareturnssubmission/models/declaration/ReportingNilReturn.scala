package uk.gov.hmrc.disareturnssubmission.models.declaration

import play.api.libs.json.{Json, OFormat}

case class ReportingNilReturn(nilReturn: Boolean)

object ReportingNilReturn {
  implicit val format: OFormat[ReportingNilReturn] = Json.format[ReportingNilReturn]
}
