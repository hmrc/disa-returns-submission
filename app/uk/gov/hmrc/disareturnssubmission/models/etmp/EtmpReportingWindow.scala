package uk.gov.hmrc.disareturnssubmission.models.etmp

import play.api.libs.json.{Json, OFormat}

case class EtmpReportingWindow(reportingWindowOpen: Boolean)

object EtmpReportingWindow {
  implicit val format: OFormat[EtmpReportingWindow] = Json.format[EtmpReportingWindow]
}
