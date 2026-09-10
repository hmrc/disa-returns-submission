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

import play.api.Logging
import play.api.libs.json.{JsValue, Json, Reads}
import play.api.mvc.{Action, AnyContent, ControllerComponents, Result}
import uk.gov.hmrc.disareturnssubmission.testOnly.models.{DeleteTestOverridesRequest, TestOverride, TestOverrideRequest}
import uk.gov.hmrc.disareturnssubmission.testOnly.services.TestOverrideService
import uk.gov.hmrc.disareturnssubmission.validators.ZReferenceValidator
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class TestOnlyOverridesController @Inject() (
  cc: ControllerComponents,
  service: TestOverrideService,
  zReferenceValidator: ZReferenceValidator
)(implicit ec: ExecutionContext)
    extends BackendController(cc)
    with Logging {

  def get(zReference: String): Action[AnyContent] = Action.async {
    withZReference(zReference)(service.get)
  }

  def put(): Action[JsValue] = Action.async(parse.json) { request =>
    withValidRequest[TestOverrideRequest](request.body, "invalid override request") { overrideRequest =>
      withValidZReferences(overrideRequest.zReferences) { zReferences =>
        service
          .replace(zReferences, overrideRequest)
          .map(_ => NoContent)
          .recover(repositoryFailure("replace", zReferences.size))
      }
    }
  }

  def delete(): Action[JsValue] = Action.async(parse.json) { request =>
    withValidRequest[DeleteTestOverridesRequest](request.body, "invalid delete request") { deleteRequest =>
      withValidZReferences(deleteRequest.zReferences) { zReferences =>
        service
          .delete(zReferences)
          .map(_ => NoContent)
          .recover(repositoryFailure("delete", zReferences.size))
      }
    }
  }

  private def withZReference(zReference: String)(f: String => Future[TestOverride]): Future[Result] =
    zReferenceValidator
      .normalize(zReference)
      .map(normalized => f(normalized).map(context => Ok(Json.toJson(context))))
      .getOrElse(badRequest("invalid zReference"))

  private def withValidRequest[A: Reads](body: JsValue, error: String)(f: A => Future[Result]): Future[Result] =
    body.validate[A].fold(_ => badRequest(error), f)

  private def withValidZReferences(zReferences: Seq[String])(f: Seq[String] => Future[Result]): Future[Result] = {
    val normalized = zReferences.map(zReferenceValidator.normalize)
    if (zReferences.isEmpty || normalized.exists(_.isEmpty)) {
      badRequest("zReferences must be a non-empty array of valid Z-references")
    } else {
      f(normalized.flatten.distinct)
    }
  }

  private def badRequest(message: String): Future[Result] =
    Future.successful(BadRequest(Json.obj("message" -> message)))

  private def repositoryFailure(operation: String, zReferenceCount: Int): PartialFunction[Throwable, Result] = {
    case NonFatal(exception) =>
      logger.error(
        s"[TestOnlyOverridesController][$operation] Failed to $operation overrides for [$zReferenceCount] Z-references",
        exception
      )
      ServiceUnavailable
  }
}
