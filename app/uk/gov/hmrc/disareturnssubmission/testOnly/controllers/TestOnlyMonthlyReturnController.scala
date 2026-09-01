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
import play.api.libs.json.JsValue
import play.api.mvc.{Action, ControllerComponents}
import uk.gov.hmrc.disareturnssubmission.models.ZReference
import uk.gov.hmrc.disareturnssubmission.repositories.MonthlyReturnRepository
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class TestOnlyMonthlyReturnController @Inject() (
  cc: ControllerComponents,
  monthlyReturnRepository: MonthlyReturnRepository
)(implicit ec: ExecutionContext)
    extends BackendController(cc)
    with Logging {

  def delete(): Action[JsValue] = Action.async(parse.json) { request =>
    (request.body \ "zReferences").validate[Seq[String]].asOpt match {
      case Some(zReferences) if zReferences.nonEmpty =>
        val normalized = zReferences.map(ZReference.normalize)

        if (normalized.exists(_.isEmpty)) {
          Future.successful(BadRequest)
        } else {
          val normalizedZReferences = normalized.flatten.distinct

          monthlyReturnRepository
            .deleteByZReferences(normalizedZReferences)
            .map { deletedCount =>
              logger.info(
                s"[TestOnlyMonthlyReturnController][delete] Deleted [$deletedCount] monthly returns for [${normalizedZReferences.size}] Z-references"
              )
              NoContent
            }
            .recover { case NonFatal(exception) =>
              logger.error(
                s"[TestOnlyMonthlyReturnController][delete] Failed to delete monthly returns for [${normalizedZReferences.size}] Z-references",
                exception
              )
              ServiceUnavailable
            }
        }

      case _ => Future.successful(BadRequest)
    }
  }

}
