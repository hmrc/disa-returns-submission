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
import play.api.mvc.*
import uk.gov.hmrc.disareturnssubmission.models.monthly.MonthlyReturnReportingWindowStatus
import uk.gov.hmrc.disareturnssubmission.services.MonthlyReturnReportingWindowService
import uk.gov.hmrc.disareturnssubmission.validators.ZReferenceValidator
import uk.gov.hmrc.internalauth.client.*
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.Inject
import scala.concurrent.*

class MonthlyReturnReportingWindowController @Inject() (
  cc: ControllerComponents,
  reportingWindowService: MonthlyReturnReportingWindowService,
  auth: BackendAuthComponents,
  zReferenceValidator: ZReferenceValidator
)(implicit ec: ExecutionContext)
    extends BackendController(cc)
    with Logging {

  def status(zReference: String): Action[AnyContent] =
    auth.authorizedAction(readPermission).async {
      zReferenceValidator.normalize(zReference) match {
        case Some(normalizedZReference) =>
          reportingWindowService.resolve(normalizedZReference).map { resolved =>
            logger.info(
              s"[MonthlyReturnReportingWindowController][status] Reporting window open for Z-reference [$normalizedZReference]: [${resolved.isOpen}]"
            )
            Ok(
              Json.toJson(
                MonthlyReturnReportingWindowStatus(
                  reportingWindowOpen = resolved.isOpen,
                  reportingWindowStart = resolved.windowStart,
                  reportingWindowEnd = resolved.windowEnd,
                  resolvedAt = resolved.instant
                )
              )
            )
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
