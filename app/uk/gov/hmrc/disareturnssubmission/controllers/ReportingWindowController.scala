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

package uk.gov.hmrc.disareturnssubmission.controllers

import play.api.Logging
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.hmrc.disareturnssubmission.models.{ReportingWindowStatus, ZReference}
import uk.gov.hmrc.disareturnssubmission.services.ReportingWindowService
import uk.gov.hmrc.internalauth.client.{BackendAuthComponents, IAAction, Predicate, Resource, ResourceLocation, ResourceType}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class ReportingWindowController @Inject() (
  cc: ControllerComponents,
  reportingWindowService: ReportingWindowService,
  auth: BackendAuthComponents
)(implicit ec: ExecutionContext)
    extends BackendController(cc)
    with Logging {

  def status(zReference: String): Action[AnyContent] =
    auth.authorizedAction(readPermission).async {
      ZReference.normalize(zReference) match {
        case Some(normalizedZReference) =>
          reportingWindowService.isOpen(normalizedZReference).map { reportingWindowOpen =>
            logger.info(
              s"[ReportingWindowController][status] Reporting window open for Z-reference [$normalizedZReference]: [$reportingWindowOpen]"
            )
            Ok(Json.toJson(ReportingWindowStatus(reportingWindowOpen)))
          }
        case None                       => Future.successful(BadRequest(Json.obj("error" -> "Invalid zReference")))
      }
    }

  private val readPermission: Predicate =
    Predicate.Permission(
      Resource(ResourceType("disa-returns-submission"), ResourceLocation("*")),
      IAAction("READ")
    )
}
