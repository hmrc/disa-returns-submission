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

import base.SpecBase
import org.mockito.ArgumentMatchers.{any, eq as eqTo}
import org.mockito.Mockito.{reset, verify, verifyNoInteractions, when}
import org.scalatest.BeforeAndAfterEach
import play.api.Application
import play.api.http.HeaderNames.{AUTHORIZATION, LOCATION}
import play.api.inject.bind
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers.*
import uk.gov.hmrc.disareturnssubmission.models.*
import uk.gov.hmrc.disareturnssubmission.services.CreateMonthlyReturnResult.*
import uk.gov.hmrc.disareturnssubmission.services.{CreateMonthlyReturnResult, DeclareMonthlyReturnResult, MonthlyReturnService, SubmitReturnResult}

import scala.concurrent.Future

class MonthlyReturnControllerSpec extends SpecBase with BeforeAndAfterEach {

  private val mockMonthlyReturnService = mock[MonthlyReturnService]

  override lazy val app: Application = applicationBuilder(
    Seq(
      bind[MonthlyReturnService].toInstance(mockMonthlyReturnService)
    )
  ).build()

  private lazy val controller = inject[MonthlyReturnController]

  private val path                   = s"/monthly/$testZReference/$testTaxYear/$testMonth"
  private val declarationsPath       = s"$path/declarations"
  private val submissionsPath        = s"$path/submissions/$testUploadReference"
  private val validInternalAuthToken = "valid-internal-auth-token-disa-returns-backend"

  private def authorizedRequest(method: String, path: String) =
    FakeRequest(method, path).withHeaders(AUTHORIZATION -> validInternalAuthToken)

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    reset(mockMonthlyReturnService)
  }

  "MonthlyReturnController" - {

    "MonthlyReturnController.create" - {

      "must return CREATED with Location header when the MonthlyReturn is created" in {
        when(
          mockMonthlyReturnService.create(
            eqTo(testZReference),
            eqTo(testTaxYear),
            eqTo(testMonth),
            eqTo(true)
          )
        )
          .thenReturn(Future.successful(Created(testSubmissionId)))

        val result = controller.create(testZReference, testTaxYear, testMonth)(
          authorizedRequest("POST", path).withBody(
            Json.toJson(CreateMonthlyReturnRequest(nilReturn = true))
          )
        )

        status(result) mustBe CREATED
        header(LOCATION, result).value mustBe path
        contentAsJson(result) mustBe Json.toJson(CreateMonthlyReturnResponse(testSubmissionId))
      }

      "must return CONFLICT when the MonthlyReturn already exists" in {
        when(
          mockMonthlyReturnService.create(
            eqTo(testZReference),
            eqTo(testTaxYear),
            eqTo(testMonth),
            eqTo(false)
          )
        )
          .thenReturn(Future.successful(AlreadyExists(testSubmissionId)))

        val result = controller.create(testZReference, testTaxYear, testMonth)(
          authorizedRequest("POST", path).withBody(
            Json.toJson(CreateMonthlyReturnRequest(nilReturn = false))
          )
        )

        status(result) mustBe CONFLICT
        contentAsJson(result) mustBe Json.toJson(CreateMonthlyReturnResponse(testSubmissionId))
      }

      "must return UNPROCESSABLE_ENTITY when the declaration period is closed" in {
        when(
          mockMonthlyReturnService.create(
            eqTo(testZReference),
            eqTo(testTaxYear),
            eqTo(testMonth),
            eqTo(false)
          )
        )
          .thenReturn(Future.successful(CreateMonthlyReturnResult.OutsideDeclarationPeriod))

        val result = controller.create(testZReference, testTaxYear, testMonth)(
          authorizedRequest("POST", path).withBody(
            Json.toJson(CreateMonthlyReturnRequest(nilReturn = false))
          )
        )

        status(result) mustBe UNPROCESSABLE_ENTITY
      }

      "must return SERVICE_UNAVAILABLE when the service fails" in {
        when(
          mockMonthlyReturnService.create(
            eqTo(testZReference),
            eqTo(testTaxYear),
            eqTo(testMonth),
            eqTo(false)
          )
        )
          .thenReturn(Future.failed(new RuntimeException(testMongoDownMessage)))

        val result = controller.create(testZReference, testTaxYear, testMonth)(
          authorizedRequest("POST", path).withBody(
            Json.toJson(CreateMonthlyReturnRequest(nilReturn = false))
          )
        )

        status(result) mustBe SERVICE_UNAVAILABLE
      }

      "must return BAD_REQUEST when nilReturn is not a boolean" in {
        val result = controller.create(testZReference, testTaxYear, testMonth)(
          authorizedRequest("POST", path).withBody(
            Json.obj(nilReturnFieldName -> "false")
          )
        )

        status(result) mustBe BAD_REQUEST
      }
    }

    "MonthlyReturnController.get" - {

      "must return OK when the MonthlyReturn exists" in {
        when(mockMonthlyReturnService.get(eqTo(testZReference), eqTo(testTaxYear), eqTo(testMonth)))
          .thenReturn(Future.successful(Some(monthlyReturn)))

        val result =
          controller.get(lowercaseTestZReference, testTaxYear, testMonth)(authorizedRequest("GET", path))

        status(result) mustBe OK
        contentAsJson(result) mustBe Json.toJson(monthlyReturn)
        verify(mockMonthlyReturnService).get(eqTo(testZReference), eqTo(testTaxYear), eqTo(testMonth))
      }

      "must return NOT_FOUND when the MonthlyReturn does not exist" in {
        when(mockMonthlyReturnService.get(eqTo(testZReference), eqTo(testTaxYear), eqTo(testMonth)))
          .thenReturn(Future.successful(None))

        val result = controller.get(testZReference, testTaxYear, testMonth)(authorizedRequest("GET", path))

        status(result) mustBe NOT_FOUND
      }

      "must return SERVICE_UNAVAILABLE when the service fails" in {
        when(mockMonthlyReturnService.get(eqTo(testZReference), eqTo(testTaxYear), eqTo(testMonth)))
          .thenReturn(Future.failed(new RuntimeException(testMongoDownMessage)))

        val result = controller.get(testZReference, testTaxYear, testMonth)(authorizedRequest("GET", path))

        status(result) mustBe SERVICE_UNAVAILABLE
      }

      "must return BAD_REQUEST when path parameters are invalid" in {
        val result =
          controller.get(invalidTestZReference, testTaxYear, testMonth)(authorizedRequest("GET", path))

        status(result) mustBe BAD_REQUEST
        contentAsString(result) must include(zReferenceFieldName)
      }
    }

    "MonthlyReturnController.declare" - {

      "must return OK when the MonthlyReturn is declared with nilReturn false" in {
        when(
          mockMonthlyReturnService.declare(
            eqTo(testZReference),
            eqTo(testTaxYear),
            eqTo(testMonth),
            eqTo(false),
            eqTo(List.empty)
          )
        )
          .thenReturn(Future.successful(DeclareMonthlyReturnResult.Declared))

        val result = controller.declare(lowercaseTestZReference, testTaxYear, testMonth)(
          authorizedRequest("POST", declarationsPath).withBody(Json.obj("nilReturn" -> false))
        )

        status(result) mustBe OK
        verify(mockMonthlyReturnService)
          .declare(eqTo(testZReference), eqTo(testTaxYear), eqTo(testMonth), eqTo(false), eqTo(List.empty))
      }

      "must pass pending submission IDs to the service" in {
        val pendingSubmissionIds = List(testUploadReference, "second-reference")
        when(
          mockMonthlyReturnService.declare(
            eqTo(testZReference),
            eqTo(testTaxYear),
            eqTo(testMonth),
            eqTo(false),
            eqTo(pendingSubmissionIds)
          )
        ).thenReturn(Future.successful(DeclareMonthlyReturnResult.Declared))

        val result = controller.declare(testZReference, testTaxYear, testMonth)(
          authorizedRequest("POST", declarationsPath).withBody(
            Json.obj(
              "nilReturn"            -> false,
              "pendingSubmissionIds" -> pendingSubmissionIds
            )
          )
        )

        status(result) mustBe OK
      }

      "must return OK when the MonthlyReturn is declared with nilReturn true" in {
        when(
          mockMonthlyReturnService.declare(
            eqTo(testZReference),
            eqTo(testTaxYear),
            eqTo(testMonth),
            eqTo(true),
            eqTo(List.empty)
          )
        )
          .thenReturn(Future.successful(DeclareMonthlyReturnResult.Declared))

        val result = controller.declare(testZReference, testTaxYear, testMonth)(
          authorizedRequest("POST", declarationsPath).withBody(Json.obj("nilReturn" -> true))
        )

        status(result) mustBe OK
        verify(mockMonthlyReturnService)
          .declare(eqTo(testZReference), eqTo(testTaxYear), eqTo(testMonth), eqTo(true), eqTo(List.empty))
      }

      "must return BAD_REQUEST when pending submission IDs are supplied for a nil return" in {
        val pendingSubmissionIds = List(testUploadReference)
        when(
          mockMonthlyReturnService.declare(
            eqTo(testZReference),
            eqTo(testTaxYear),
            eqTo(testMonth),
            eqTo(true),
            eqTo(pendingSubmissionIds)
          )
        ).thenReturn(Future.successful(DeclareMonthlyReturnResult.PendingSubmissionsForNilReturn))

        val result = controller.declare(testZReference, testTaxYear, testMonth)(
          authorizedRequest("POST", declarationsPath).withBody(
            Json.obj(
              "nilReturn"            -> true,
              "pendingSubmissionIds" -> pendingSubmissionIds
            )
          )
        )

        status(result) mustBe BAD_REQUEST
      }

      "must return UNPROCESSABLE_ENTITY when the MonthlyReturn has already been declared" in {
        when(
          mockMonthlyReturnService.declare(
            eqTo(testZReference),
            eqTo(testTaxYear),
            eqTo(testMonth),
            eqTo(false),
            eqTo(List.empty)
          )
        )
          .thenReturn(Future.successful(DeclareMonthlyReturnResult.AlreadyDeclared))

        val result = controller.declare(testZReference, testTaxYear, testMonth)(
          authorizedRequest("POST", declarationsPath).withBody(Json.obj("nilReturn" -> false))
        )

        status(result) mustBe UNPROCESSABLE_ENTITY
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "MONTHLY_RETURN_ALREADY_DECLARED",
          "message" -> "This monthly return was already declared"
        )
      }

      "must return NOT_FOUND when no monthly return data has been submitted" in {
        when(
          mockMonthlyReturnService.declare(
            eqTo(testZReference),
            eqTo(testTaxYear),
            eqTo(testMonth),
            eqTo(false),
            eqTo(List.empty)
          )
        )
          .thenReturn(Future.successful(DeclareMonthlyReturnResult.MonthlyReturnNotFound))

        val result = controller.declare(testZReference, testTaxYear, testMonth)(
          authorizedRequest("POST", declarationsPath).withBody(Json.obj("nilReturn" -> false))
        )

        status(result) mustBe NOT_FOUND
      }

      "must return UNPROCESSABLE_ENTITY when the declaration period is closed" in {
        when(
          mockMonthlyReturnService.declare(
            eqTo(testZReference),
            eqTo(testTaxYear),
            eqTo(testMonth),
            eqTo(false),
            eqTo(List.empty)
          )
        )
          .thenReturn(Future.successful(DeclareMonthlyReturnResult.OutsideDeclarationPeriod))

        val result = controller.declare(testZReference, testTaxYear, testMonth)(
          authorizedRequest("POST", declarationsPath).withBody(Json.obj("nilReturn" -> false))
        )

        status(result) mustBe UNPROCESSABLE_ENTITY
        contentAsJson(result) mustBe Json.obj(
          "code"    -> "DECLARATION_PERIOD_CLOSED",
          "message" -> "Monthly declaration period is closed."
        )
      }

      "must return SERVICE_UNAVAILABLE when the service fails" in {
        when(
          mockMonthlyReturnService.declare(
            eqTo(testZReference),
            eqTo(testTaxYear),
            eqTo(testMonth),
            eqTo(false),
            eqTo(List.empty)
          )
        )
          .thenReturn(Future.failed(new RuntimeException(testMongoDownMessage)))

        val result = controller.declare(testZReference, testTaxYear, testMonth)(
          authorizedRequest("POST", declarationsPath).withBody(Json.obj("nilReturn" -> false))
        )

        status(result) mustBe SERVICE_UNAVAILABLE
      }

      "must return BAD_REQUEST when path parameters are invalid" in {
        val result = controller.declare(invalidTestZReference, testTaxYear, testMonth)(
          authorizedRequest("POST", declarationsPath).withBody(Json.obj("nilReturn" -> false))
        )

        status(result) mustBe BAD_REQUEST
        contentAsString(result) must include(zReferenceFieldName)
      }
    }
  }
  "MonthlyReturnController.submit" - {
    "must return OK when the MonthlyReturn is uploaded" in {
      when(
        mockMonthlyReturnService.storeSubmission(
          eqTo(testZReference),
          eqTo(testTaxYear),
          eqTo(testMonth),
          eqTo(testUploadReference),
          any()
        )
      )
        .thenReturn(Future.successful(SubmitReturnResult.UpdateSuccessful))

      val result = controller.storeSubmission(lowercaseTestZReference, testTaxYear, testMonth, testUploadReference)(
        authorizedRequest("PUT", submissionsPath)
          .withHeaders("Content-Type" -> "application/x-ndjson")
          .withBody(testTempFile)
      )

      status(result) mustBe OK
    }

    "must return UnsupportedMediaType when the wrong filetype is uploaded" in {
      val result = controller.storeSubmission(lowercaseTestZReference, testTaxYear, testMonth, testUploadReference)(
        authorizedRequest("PUT", submissionsPath)
          .withHeaders("Content-Type" -> "something wrong")
          .withBody(testTempFile)
      )

      status(result) mustBe UNSUPPORTED_MEDIA_TYPE
      contentAsString(result) must include("Content-Type must be application/x-ndjson")
      verifyNoInteractions(mockMonthlyReturnService)
    }

    "must return CONFLICT when the submission cannot be stored" in {
      when(
        mockMonthlyReturnService.storeSubmission(
          any(),
          any(),
          any(),
          eqTo(testUploadReference),
          any()
        )
      ).thenReturn(Future.successful(SubmitReturnResult.SubmissionConflict))

      val result = controller.storeSubmission(lowercaseTestZReference, testTaxYear, testMonth, testUploadReference)(
        authorizedRequest("PUT", submissionsPath)
          .withHeaders("Content-Type" -> "application/x-ndjson")
          .withBody(testTempFile)
      )

      status(result) mustBe CONFLICT
    }

    "must return SERVICE_UNAVAILABLE when storing fails" in {
      when(
        mockMonthlyReturnService.storeSubmission(
          any(),
          any(),
          any(),
          eqTo(testUploadReference),
          any()
        )
      ).thenReturn(Future.failed(new RuntimeException(testMongoDownMessage)))

      val result = controller.storeSubmission(lowercaseTestZReference, testTaxYear, testMonth, testUploadReference)(
        authorizedRequest("PUT", submissionsPath)
          .withHeaders("Content-Type" -> "application/x-ndjson")
          .withBody(testTempFile)
      )

      status(result) mustBe SERVICE_UNAVAILABLE
    }

    "must return NOT_FOUND when the MonthlyReturn does not exist" in {
      when(
        mockMonthlyReturnService.storeSubmission(
          any(),
          any(),
          any(),
          eqTo(testUploadReference),
          any()
        )
      )
        .thenReturn(Future.successful(SubmitReturnResult.MonthlyReturnNotFound))

      val result = controller.storeSubmission(lowercaseTestZReference, testTaxYear, testMonth, testUploadReference)(
        authorizedRequest("PUT", submissionsPath)
          .withHeaders("Content-Type" -> "application/x-ndjson")
          .withBody(testTempFile)
      )

      status(result) mustBe NOT_FOUND
      contentAsString(result) must include("Monthly return not found")
    }

    "must return BAD_REQUEST when the submission endpoint is called with no Body" in {
      when(
        mockMonthlyReturnService.storeSubmission(
          any(),
          any(),
          any(),
          eqTo(testUploadReference),
          any()
        )
      )
        .thenReturn(Future.successful(SubmitReturnResult.NoBody))

      val result = controller.storeSubmission(lowercaseTestZReference, testTaxYear, testMonth, testUploadReference)(
        authorizedRequest("PUT", submissionsPath)
          .withHeaders("Content-Type" -> "application/x-ndjson")
      )

      status(result) mustBe BAD_REQUEST
    }

    "must return BAD_REQUEST when path parameters are invalid" in {
      val result = controller.storeSubmission(invalidTestZReference, testTaxYear, testMonth, testUploadReference)(
        authorizedRequest("PUT", submissionsPath)
          .withHeaders("Content-Type" -> "application/x-ndjson")
          .withBody(testTempFile)
      )

      status(result) mustBe BAD_REQUEST
      verifyNoInteractions(mockMonthlyReturnService)
    }
  }
}
