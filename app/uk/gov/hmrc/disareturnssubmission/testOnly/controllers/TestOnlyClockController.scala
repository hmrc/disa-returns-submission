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

import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, ControllerComponents, Result}
import uk.gov.hmrc.disareturnssubmission.models.ZReference
import uk.gov.hmrc.disareturnssubmission.testOnly.OverridableClock
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import java.time.{DateTimeException, LocalDate, ZoneOffset}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class TestOnlyClockController @Inject() (
  cc: ControllerComponents,
  clock: OverridableClock
)(implicit ec: ExecutionContext)
    extends BackendController(cc) {

  def getClock(zReference: String): Action[AnyContent] = Action.async {
    withZReference(zReference)(clockJson)
  }

  def setDate(zReference: String, date: String): Action[AnyContent] = Action.async {
    try
      withZReference(zReference) { normalized =>
        clock.setDate(normalized, LocalDate.parse(date)).flatMap(_ => clockJson(normalized))
      }
    catch {
      case _: DateTimeException =>
        Future.successful(BadRequest(Json.obj("message" -> "date must be in yyyy-MM-dd format")))
    }
  }

  def resetClock(zReference: String): Action[AnyContent] = Action.async {
    withZReference(zReference) { normalized =>
      clock.reset(normalized).flatMap(_ => clockJson(normalized))
    }
  }

  private def withZReference(zReference: String)(f: String => Future[Result]): Future[Result] =
    ZReference
      .normalize(zReference)
      .map(f)
      .getOrElse(Future.successful(BadRequest(Json.obj("message" -> "invalid zReference"))))

  private def clockJson(zReference: String): Future[Result] =
    clock.instant(zReference).map { instant =>
      Ok(
        Json.obj(
          "zReference" -> zReference,
          "date"       -> LocalDate.ofInstant(instant, ZoneOffset.UTC).toString,
          "instant"    -> instant.toString
        )
      )
    }
}
