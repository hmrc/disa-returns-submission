package uk.gov.hmrc.disareturnssubmission.models

import play.api.libs.json.{Json, OFormat}

final case class CreateMonthlyReturnRequest(nilReturn: Boolean)

object CreateMonthlyReturnRequest {
  implicit val format: OFormat[CreateMonthlyReturnRequest] = Json.format[CreateMonthlyReturnRequest]
}
