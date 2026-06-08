package uk.gov.hmrc.disareturnssubmission.models.etmp

import play.api.libs.json.{Json, OFormat}

case class EtmpObligations(obligationAlreadyMet: Boolean)

object EtmpObligations {
  implicit val format: OFormat[EtmpObligations] = Json.format[EtmpObligations]
}