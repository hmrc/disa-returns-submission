package uk.gov.hmrc.disareturnssubmission.models.summary

import play.api.libs.json.{Json, OFormat}

case class NotificationContext(clientId: String, boxId: Option[String], zReference: String)

object NotificationContext {
  implicit val mongoFormat: OFormat[NotificationContext] = Json.format[NotificationContext]
}
