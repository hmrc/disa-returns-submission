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
import uk.gov.hmrc.disareturnssubmission.models.{CreateMonthlyReturnRequest, CreateMonthlyReturnResponse}
import uk.gov.hmrc.disareturnssubmission.services.{CreateMonthlyReturnResult, DeclareMonthlyReturnResult, FileUploadService, MonthlyReturnService, SubmitReturnResult}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.play.bootstrap.controller.WithJsonBody
import uk.gov.hmrc.disareturnssubmission.validators.ValidationHelper

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import org.apache.pekko.stream.scaladsl.{Source as PekkoSource, *}
import org.apache.pekko.util.ByteString
import play.api.libs.Files
import uk.gov.hmrc.disareturnssubmission.config.AppConfig
import uk.gov.hmrc.disareturnssubmission.utils.UuidGenerator

class MonthlyReturnController @Inject() (
  cc: ControllerComponents,
  monthlyReturnService: MonthlyReturnService,
  fileUploadService: FileUploadService,
  uuidGenerator: UuidGenerator,
  appConfig: AppConfig,
  implicit val mat: Materializer
)(implicit ec: ExecutionContext)
    extends BackendController(cc)
    with WithJsonBody
    with Logging {

  def create(zReference: String, taxYear: String, month: Int): Action[JsValue] =
    Action.async(parse.json) { implicit request =>
      withValidMonthlyReturnParams(zReference, taxYear, month) { (validZReference, validTaxYear, validMonth) =>
        withJsonBody[CreateMonthlyReturnRequest] { createRequest =>
          logger.info(
            s"[MonthlyReturnController][createMonthlyReturn] Create monthly return request for zReference [$validZReference], taxYear [$validTaxYear], month [$validMonth], nilReturn [${createRequest.nilReturn}]"
          )

          monthlyReturnService
            .create(validZReference, validTaxYear, validMonth, createRequest.nilReturn, createRequest.submissionId)
            .map {
              case CreateMonthlyReturnResult.Created(submissionId)    =>
                Created(Json.toJson(CreateMonthlyReturnResponse(submissionId))).withHeaders(LOCATION -> request.path)
              case CreateMonthlyReturnResult.AlreadyExists            =>
                Conflict(Json.obj("ERROR" -> "This Monthly return already exists."))
              case CreateMonthlyReturnResult.OutsideDeclarationPeriod =>
                UnprocessableEntity(
                  Json.obj("ERROR" -> "It is not possible to create a monthly return outside its declaration period")
                )
            }
            .recover { case NonFatal(_) => ServiceUnavailable }
        }
      }
    }

  def get(zReference: String, taxYear: String, month: Int): Action[AnyContent] =
    Action.async {
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

  def declare(zReference: String, taxYear: String, month: Int): Action[AnyContent] =
    Action.async {
      logger.info(
        s"[MonthlyReturnController][declareMonthlyReturn] Declare monthly return request for zReference [$zReference], taxYear [$taxYear], month [$month]"
      )

      withValidMonthlyReturnParams(zReference, taxYear, month) { (validZReference, validTaxYear, validMonth) =>
        monthlyReturnService
          .declare(validZReference, validTaxYear, validMonth)
          .map {
            case DeclareMonthlyReturnResult.Declared                 => Ok
            case DeclareMonthlyReturnResult.AlreadyDeclared          =>
              UnprocessableEntity(Json.obj("ERROR" -> "This monthly return was already declared"))
            case DeclareMonthlyReturnResult.MonthlyReturnNotFound    =>
              NotFound(Json.obj("ERROR" -> "Monthly return not found"))
            case DeclareMonthlyReturnResult.OutsideDeclarationPeriod =>
              UnprocessableEntity(Json.obj("ERROR" -> "Monthly declaration period is closed."))
          }
          .recover { case NonFatal(_) => ServiceUnavailable }
      }
    }

  def submitReturn(zReference: String, taxYear: String, month: Int): Action[Files.TemporaryFile] =
    Action.async(parse.temporaryFile(maxLength = appConfig.maxContentLength)) { request =>
      request.contentType.filter(_ == appConfig.contentType) match {
        case Some(_) =>
          val fileNameOrReference = uuidGenerator.randomUuid()
          fileUploadService
            .uploadFileToObjectStore(fileNameOrReference.toString, request.body.path, "application/x-ndjson")
            .flatMap {
              case Some(fileLocation) =>
                monthlyReturnService
                  .submitReturn(
                    zReference,
                    taxYear,
                    month,
                    fileNameOrReference.toString,
                    request.body.path,
                    fileLocation
                  )
                  .map {
                    case SubmitReturnResult.UpdateSuccessful       => Ok
                    case SubmitReturnResult.NotUpdatedInRepository =>
                      ServiceUnavailable(Json.obj("ERROR" -> "Mongo error"))
                    case SubmitReturnResult.MonthlyReturnNotFound  =>
                      NotFound(Json.obj("ERROR" -> "Monthly return not found"))
                  }
              case None               => Future.successful(ServiceUnavailable(Json.obj("ERROR" -> "Object-store error")))
            }

        case _ =>
          Future.successful(
            UnsupportedMediaType(
              Json.obj(
                "ERROR" -> "Content-Type must be application/x-ndjson"
              )
            )
          )
      }
    }

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
