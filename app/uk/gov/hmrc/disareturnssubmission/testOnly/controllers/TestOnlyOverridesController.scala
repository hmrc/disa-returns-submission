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
import play.api.mvc.{Action, AnyContent, ControllerComponents, Result}
import uk.gov.hmrc.disareturnssubmission.models.ZReference
import uk.gov.hmrc.disareturnssubmission.testOnly.models.{TestOverride, TestOverrideRequest}
import uk.gov.hmrc.disareturnssubmission.testOnly.services.TestOverrideService
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class TestOnlyOverridesController @Inject() (
  cc: ControllerComponents,
  service: TestOverrideService
)(implicit ec: ExecutionContext)
    extends BackendController(cc) {

  def get(zReference: String): Action[AnyContent] = Action.async {
    withZReference(zReference)(service.get)
  }

  def put(zReference: String): Action[JsValue] = Action.async(parse.json) { request =>
    ZReference.normalize(zReference) match {
      case None             => Future.successful(BadRequest(Json.obj("message" -> "invalid zReference")))
      case Some(normalized) =>
        request.body
          .validate[TestOverrideRequest]
          .fold(
            _ => Future.successful(BadRequest(Json.obj("message" -> "invalid override"))),
            overrideRequest => service.replace(normalized, overrideRequest).map(context => Ok(Json.toJson(context)))
          )
    }
  }

  def delete(zReference: String): Action[AnyContent] = Action.async {
    withZReference(zReference)(service.delete)
  }

  private def withZReference(zReference: String)(f: String => Future[TestOverride]): Future[Result] =
    ZReference
      .normalize(zReference)
      .map(normalized => f(normalized).map(context => Ok(Json.toJson(context))))
      .getOrElse(Future.successful(BadRequest(Json.obj("message" -> "invalid zReference"))))
}
