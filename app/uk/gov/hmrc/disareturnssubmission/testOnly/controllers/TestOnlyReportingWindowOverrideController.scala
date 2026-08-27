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

package uk.gov.hmrc.disareturnssubmission.testOnly.controllers

import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.hmrc.disareturnssubmission.models.ZReference
import uk.gov.hmrc.disareturnssubmission.testOnly.models.ReportingWindowOverrideRequest
import uk.gov.hmrc.disareturnssubmission.testOnly.services.MutableReportingWindowService
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class TestOnlyReportingWindowOverrideController @Inject() (
  cc: ControllerComponents,
  reportingWindowService: MutableReportingWindowService
)(implicit ec: ExecutionContext)
    extends BackendController(cc) {

  def delete(zReference: String): Action[AnyContent] = Action.async {
    ZReference.normalize(zReference) match {
      case None                       => Future.successful(BadRequest)
      case Some(normalizedZReference) => reportingWindowService.delete(normalizedZReference).map(_ => NoContent)
    }
  }

  def set(zReference: String): Action[JsValue] = Action.async(parse.json) { request =>
    ZReference.normalize(zReference) match {
      case None                       => Future.successful(BadRequest(Json.obj("error" -> "Invalid zReference")))
      case Some(normalizedZReference) =>
        request.body
          .validate[ReportingWindowOverrideRequest]
          .fold(
            _ => Future.successful(BadRequest(Json.obj("error" -> "Invalid reporting window override"))),
            overrideRequest => reportingWindowService.set(normalizedZReference, overrideRequest).map(_ => NoContent)
          )
    }
  }
}
