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

import cats.data.EitherT
import cats.implicits.*
import com.google.inject.Inject
import jakarta.inject.Singleton
import play.api.Logging
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{Action, ControllerComponents, Request}
import uk.gov.hmrc.disareturnssubmission.config.AppConfig
import uk.gov.hmrc.disareturnssubmission.controllers.actionBuilders.{AuthAction, ClientIdAction, NilReturnAction}
import uk.gov.hmrc.disareturnssubmission.controllers.parsers.StrictOptionalJsonBodyParser
import uk.gov.hmrc.disareturnssubmission.services.ETMPService
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DeclarationController @Inject() (
                                        cc:                           ControllerComponents,
                                        etmpService:                  ETMPService,
//                                        ppnsService:                  PPNSService,
//                                        npsService:                   NPSService,
//                                        notificationContextService:   NotificationContextService,
                                        authAction:                   AuthAction,
                                        clientIdAction:               ClientIdAction,
                                        nilReturnAction:              NilReturnAction,
                                        config:                       AppConfig,
                                        strictOptionalJsonBodyParser: StrictOptionalJsonBodyParser
                                      )(implicit ec: ExecutionContext)
  extends BackendController(cc)
    with Logging {

  def declare(zReference: String, taxYear: String, month: String): Action[Option[JsValue]] = ???
  
}
