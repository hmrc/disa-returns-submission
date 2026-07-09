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

import org.apache.pekko.stream.Materializer
import play.api.Logging
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{Action, AnyContent, ControllerComponents, Result}

import uk.gov.hmrc.disareturnssubmission.models.{CreateMonthlyReturnRequest, CreateMonthlyReturnResponse, DeclarationRequest}
import uk.gov.hmrc.disareturnssubmission.services.{CreateMonthlyReturnResult, DeclareMonthlyReturnResult, MonthlyReturnService, SubmitReturnResult}

import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.play.bootstrap.controller.WithJsonBody
import uk.gov.hmrc.disareturnssubmission.validators.ValidationHelper

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import play.api.libs.Files
import uk.gov.hmrc.disareturnssubmission.config.AppConfig
import uk.gov.hmrc.internalauth.client.{BackendAuthComponents, IAAction, Predicate, Resource, ResourceLocation, ResourceType}

class MonthlyReturnController @Inject() (
  cc: ControllerComponents,
  monthlyReturnService: MonthlyReturnService,
  appConfig: AppConfig,
  auth: BackendAuthComponents,
  implicit val mat: Materializer
)(implicit ec: ExecutionContext)
    extends BackendController(cc)
    with WithJsonBody
    with Logging {

  def create(zReference: String, taxYear: String, month: Int): Action[JsValue] =
    auth.authorizedAction(writePermission).async(parse.json) { implicit request =>
      withValidMonthlyReturnParams(zReference, taxYear, month) { (validZReference, validTaxYear, validMonth) =>
        withJsonBody[CreateMonthlyReturnRequest] { createRequest =>
          logger.info(
            s"[MonthlyReturnController][createMonthlyReturn] Create monthly return request for zReference [$validZReference], taxYear [$validTaxYear], month [$validMonth], nilReturn [${createRequest.nilReturn}]"
          )

          monthlyReturnService
            .create(validZReference, validTaxYear, validMonth, createRequest.nilReturn)
            .map {
              case CreateMonthlyReturnResult.Created(submissionId)       =>
                Created(Json.toJson(CreateMonthlyReturnResponse(submissionId))).withHeaders(LOCATION -> request.path)
              case CreateMonthlyReturnResult.AlreadyExists(submissionId) =>
                Conflict(Json.toJson(CreateMonthlyReturnResponse(submissionId)))
              case CreateMonthlyReturnResult.OutsideDeclarationPeriod    =>
                UnprocessableEntity(
                  Json.obj("ERROR" -> "It is not possible to create a monthly return outside its declaration period")
                )
            }
            .recover { case NonFatal(_) => ServiceUnavailable }
        }
      }
    }

  def get(zReference: String, taxYear: String, month: Int): Action[AnyContent] =
    auth.authorizedAction(readPermission).async {
      logger.info(
        s"[MonthlyReturnController][getMonthlyReturn] Get monthly return request for zReference [$zReference], taxYear [$taxYear], month [$month]"
      )

      withValidMonthlyReturnParams(zReference, taxYear, month) { (validZReference, validTaxYear, validMonth) =>
        monthlyReturnService
          .get(validZReference, validTaxYear, validMonth)
          .map {
            case Some(monthlyReturn) => Ok(Json.toJson(monthlyReturn))
            case None                => NotFound
          }
          .recover { case NonFatal(_) => ServiceUnavailable }
      }
    }

  def declare(zReference: String, taxYear: String, month: Int): Action[JsValue] =
    auth.authorizedAction(writePermission).async(parse.json) { implicit request =>
      withValidMonthlyReturnParams(zReference, taxYear, month) { (validZReference, validTaxYear, validMonth) =>
        withJsonBody[DeclarationRequest] { declarationRequest =>
          monthlyReturnService
            .declare(validZReference, validTaxYear, validMonth, declarationRequest.nilReturn)
            .map {
              case DeclareMonthlyReturnResult.Declared                 => Ok
              case DeclareMonthlyReturnResult.AlreadyDeclared          =>
                UnprocessableEntity(Json.obj("ERROR" -> "This monthly return was already declared"))
              case DeclareMonthlyReturnResult.MonthlyReturnNotFound    =>
                UnprocessableEntity(
                  Json.obj(
                    "code"  -> "NO_SUBMISSION_DATA",
                    "error" -> "Cannot declare with nilReturn as false when no monthly return data has been submitted"
                  )
                )
              case DeclareMonthlyReturnResult.OutsideDeclarationPeriod =>
                UnprocessableEntity(Json.obj("ERROR" -> "Monthly declaration period is closed."))
            }
            .recover { case NonFatal(_) => ServiceUnavailable }
        }
      }
    }

  def storeSubmission(zReference: String, taxYear: String, month: Int): Action[Files.TemporaryFile] =
    auth.authorizedAction(writePermission).async(parse.temporaryFile(maxLength = appConfig.maxContentLength)) {
      request =>
        request.contentType
          .filter(_ == appConfig.contentType) match { // TODO to be discussed if parameter validation (months etc.) is needed
          case Some(_) =>
            monthlyReturnService
              .storeSubmission(zReference, taxYear, month, bodyPath = request.body.path)
              .map {
                case SubmitReturnResult.UpdateSuccessful       => Ok
                case SubmitReturnResult.NotUpdatedInRepository =>
                  ServiceUnavailable(Json.obj("ERROR" -> "Mongo error"))
                case SubmitReturnResult.MonthlyReturnNotFound  =>
                  NotFound(Json.obj("ERROR" -> "Monthly return not found"))
                case SubmitReturnResult.NoBody                 => BadRequest(Json.obj("ERROR" -> "Request body must not be empty"))
              }
              .recover { case NonFatal(_) =>
                ServiceUnavailable
              }
          case _       =>
            Future.successful(
              UnsupportedMediaType(
                Json.obj(
                  "ERROR" -> "Content-Type must be application/x-ndjson"
                )
              )
            )
        }
    }

  private val readPermission: Predicate =
    permission("READ")

  private val writePermission: Predicate =
    permission("WRITE")

  private def permission(action: String): Predicate =
    Predicate.Permission(
      Resource(ResourceType("disa-returns-submission"), ResourceLocation("*")),
      IAAction(action)
    )

  private def withValidMonthlyReturnParams(
    zReference: String,
    taxYear: String,
    month: Int
  )(block: (String, String, Int) => Future[Result]): Future[Result] =
    ValidationHelper.validateParams(zReference, taxYear, month) match {
      case Right((validZReference, validTaxYear, validMonth)) =>
        block(validZReference, validTaxYear, validMonth)

      case Left(errorMessage) =>
        logger.warn(
          s"[MonthlyReturnController][withValidMonthlyReturnParams] Invalid monthly return request parameters for zReference [$zReference], taxYear [$taxYear], month [$month]: [$errorMessage]"
        )
        Future.successful(BadRequest(Json.obj("message" -> errorMessage)))
    }

}
