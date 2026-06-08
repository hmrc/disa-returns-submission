package uk.gov.hmrc.disareturnssubmission.models.common

import play.api.libs.json.JsValue
import play.api.mvc.{Request, WrappedRequest}

case class DeclarationRequest[A](
                                  request:           Request[A],
                                  clientId:          String,
                                  nilReturnReported: Boolean = false,
                                  parsedJson:        Option[JsValue] = None
                                ) extends WrappedRequest[A](request)