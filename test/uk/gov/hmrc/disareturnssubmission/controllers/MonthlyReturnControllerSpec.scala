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
import org.mockito.Mockito.{reset, verify, when}
import org.scalatest.BeforeAndAfterEach
import play.api.Application
import play.api.http.HeaderNames.LOCATION
import play.api.inject.bind
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers.*
import uk.gov.hmrc.disareturnssubmission.models.*
import uk.gov.hmrc.disareturnssubmission.services.CreateMonthlyReturnResult.*
import uk.gov.hmrc.disareturnssubmission.services.{CreateMonthlyReturnResult, DeclareMonthlyReturnResult, MonthlyReturnService, ObjectStoreService, SubmitReturnResult}
import uk.gov.hmrc.disareturnssubmission.utils.UuidGenerator

import java.util.UUID
import scala.concurrent.Future

class MonthlyReturnControllerSpec extends SpecBase with BeforeAndAfterEach {

  private val mockMonthlyReturnService = mock[MonthlyReturnService]
  private val mockFileUploadService    = mock[ObjectStoreService]
  private val mockUuidGenerator        = mock[UuidGenerator]

  override lazy val app: Application = applicationBuilder(
    Seq(
      bind[MonthlyReturnService].toInstance(mockMonthlyReturnService),
      bind[ObjectStoreService].toInstance(mockFileUploadService)
    )
  ).build()

  private lazy val controller = inject[MonthlyReturnController]

  private val path             = s"/monthly/$testZReference/$testTaxYear/$testMonth"
  private val declarationsPath = s"$path/declarations"
  private val submissionsPath  = s"$path/submissions"

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
          FakeRequest("POST", path).withBody(
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
          FakeRequest("POST", path).withBody(
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
          FakeRequest("POST", path).withBody(
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
          FakeRequest("POST", path).withBody(
            Json.toJson(CreateMonthlyReturnRequest(nilReturn = false))
          )
        )

        status(result) mustBe SERVICE_UNAVAILABLE
      }

      "must return BAD_REQUEST when nilReturn is not a boolean" in {
        val result = controller.create(testZReference, testTaxYear, testMonth)(
          FakeRequest("POST", path).withBody(
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
          controller.get(lowercaseTestZReference, testTaxYear, testMonth)(FakeRequest("GET", path))

        status(result) mustBe OK
        contentAsJson(result) mustBe Json.toJson(monthlyReturn)
        verify(mockMonthlyReturnService).get(eqTo(testZReference), eqTo(testTaxYear), eqTo(testMonth))
      }

      "must return NOT_FOUND when the MonthlyReturn does not exist" in {
        when(mockMonthlyReturnService.get(eqTo(testZReference), eqTo(testTaxYear), eqTo(testMonth)))
          .thenReturn(Future.successful(None))

        val result = controller.get(testZReference, testTaxYear, testMonth)(FakeRequest("GET", path))

        status(result) mustBe NOT_FOUND
      }

      "must return SERVICE_UNAVAILABLE when the service fails" in {
        when(mockMonthlyReturnService.get(eqTo(testZReference), eqTo(testTaxYear), eqTo(testMonth)))
          .thenReturn(Future.failed(new RuntimeException(testMongoDownMessage)))

        val result = controller.get(testZReference, testTaxYear, testMonth)(FakeRequest("GET", path))

        status(result) mustBe SERVICE_UNAVAILABLE
      }

      "must return BAD_REQUEST when path parameters are invalid" in {
        val result =
          controller.get(invalidTestZReference, testTaxYear, testMonth)(FakeRequest("GET", path))

        status(result) mustBe BAD_REQUEST
        contentAsString(result) must include(zReferenceFieldName)
      }
    }

    "MonthlyReturnController.declare" - {

      "must return OK when the MonthlyReturn is declared with nilReturn false" in {
        when(mockMonthlyReturnService.declare(eqTo(testZReference), eqTo(testTaxYear), eqTo(testMonth), eqTo(false)))
          .thenReturn(Future.successful(DeclareMonthlyReturnResult.Declared))

        val result = controller.declare(lowercaseTestZReference, testTaxYear, testMonth)(
          FakeRequest("POST", declarationsPath).withBody(Json.obj("nilReturn" -> false))
        )

        status(result) mustBe OK
        verify(mockMonthlyReturnService).declare(eqTo(testZReference), eqTo(testTaxYear), eqTo(testMonth), eqTo(false))
      }

      "must return OK when the MonthlyReturn is declared with nilReturn true" in {
        when(mockMonthlyReturnService.declare(eqTo(testZReference), eqTo(testTaxYear), eqTo(testMonth), eqTo(true)))
          .thenReturn(Future.successful(DeclareMonthlyReturnResult.Declared))

        val result = controller.declare(testZReference, testTaxYear, testMonth)(
          FakeRequest("POST", declarationsPath).withBody(Json.obj("nilReturn" -> true))
        )

        status(result) mustBe OK
        verify(mockMonthlyReturnService).declare(eqTo(testZReference), eqTo(testTaxYear), eqTo(testMonth), eqTo(true))
      }

      "must return UNPROCESSABLE_ENTITY when the MonthlyReturn has already been declared" in {
        when(mockMonthlyReturnService.declare(eqTo(testZReference), eqTo(testTaxYear), eqTo(testMonth), eqTo(false)))
          .thenReturn(Future.successful(DeclareMonthlyReturnResult.AlreadyDeclared))

        val result = controller.declare(testZReference, testTaxYear, testMonth)(
          FakeRequest("POST", declarationsPath).withBody(Json.obj("nilReturn" -> false))
        )

        status(result) mustBe UNPROCESSABLE_ENTITY
      }

      "must return UNPROCESSABLE_ENTITY with NO_SUBMISSION_DATA code when nilReturn is false but no monthly return data has been submitted" in {
        when(mockMonthlyReturnService.declare(eqTo(testZReference), eqTo(testTaxYear), eqTo(testMonth), eqTo(false)))
          .thenReturn(Future.successful(DeclareMonthlyReturnResult.MonthlyReturnNotFound))

        val result = controller.declare(testZReference, testTaxYear, testMonth)(
          FakeRequest("POST", declarationsPath).withBody(Json.obj("nilReturn" -> false))
        )

        status(result) mustBe UNPROCESSABLE_ENTITY
        (contentAsJson(result) \ "code").as[String] mustBe "NO_SUBMISSION_DATA"
      }

      "must return UNPROCESSABLE_ENTITY when the declaration period is closed" in {
        when(mockMonthlyReturnService.declare(eqTo(testZReference), eqTo(testTaxYear), eqTo(testMonth), eqTo(false)))
          .thenReturn(Future.successful(DeclareMonthlyReturnResult.OutsideDeclarationPeriod))

        val result = controller.declare(testZReference, testTaxYear, testMonth)(
          FakeRequest("POST", declarationsPath).withBody(Json.obj("nilReturn" -> false))
        )

        status(result) mustBe UNPROCESSABLE_ENTITY
      }

      "must return SERVICE_UNAVAILABLE when the service fails" in {
        when(mockMonthlyReturnService.declare(eqTo(testZReference), eqTo(testTaxYear), eqTo(testMonth), eqTo(false)))
          .thenReturn(Future.failed(new RuntimeException(testMongoDownMessage)))

        val result = controller.declare(testZReference, testTaxYear, testMonth)(
          FakeRequest("POST", declarationsPath).withBody(Json.obj("nilReturn" -> false))
        )

        status(result) mustBe SERVICE_UNAVAILABLE
      }

      "must return BAD_REQUEST when path parameters are invalid" in {
        val result = controller.declare(invalidTestZReference, testTaxYear, testMonth)(
          FakeRequest("POST", declarationsPath).withBody(Json.obj("nilReturn" -> false))
        )

        status(result) mustBe BAD_REQUEST
        contentAsString(result) must include(zReferenceFieldName)
      }
    }
  }
  "MonthlyReturnController.submit" - {
    "must return OK when the MonthlyReturn is uploaded" in {
      when(mockUuidGenerator.randomUuid()).thenReturn(UUID.fromString(testUploadReference))

      when(mockMonthlyReturnService.get(any(), any(), any())).thenReturn(Future(Some(monthlyReturn)))
      when(mockFileUploadService.uploadFileToObjectStore(any(), any(), any(), any(), any()))
        .thenReturn(Future("test/test"))

      when(
        mockMonthlyReturnService.storeSubmission(
          any(),
          any(),
          any(),
          any()
        )
      )
        .thenReturn(Future.successful(SubmitReturnResult.UpdateSuccessful))

      val result = controller.storeSubmission(lowercaseTestZReference, testTaxYear, testMonth)(
        FakeRequest("POST", submissionsPath)
          .withHeaders("Content-Type" -> "application/x-ndjson")
          .withBody(testTempFile)
      )

      status(result) mustBe OK
    }

    "must return UnsupportedMediaType when the wrong filetype is uploaded" in {
      when(mockUuidGenerator.randomUuid()).thenReturn(UUID.fromString(testUploadReference))

      when(mockMonthlyReturnService.get(any(), any(), any())).thenReturn(Future(Some(monthlyReturn)))
      when(mockFileUploadService.uploadFileToObjectStore(any(), any(), any(), any(), any()))
        .thenReturn(Future("test/test"))

      when(
        mockMonthlyReturnService.storeSubmission(
          any(),
          any(),
          any(),
          any()
        )
      )
        .thenReturn(Future.successful(SubmitReturnResult.UpdateSuccessful))

      val result = controller.storeSubmission(lowercaseTestZReference, testTaxYear, testMonth)(
        FakeRequest("POST", submissionsPath)
          .withHeaders("Content-Type" -> "something wrong")
          .withBody(testTempFile)
      )

      status(result) mustBe UNSUPPORTED_MEDIA_TYPE
      contentAsString(result) must include("Content-Type must be application/x-ndjson")
    }

    "must return ServiceUnavailable when Mongo gives back an error" in {
      when(mockUuidGenerator.randomUuid()).thenReturn(UUID.fromString(testUploadReference))

      when(mockMonthlyReturnService.get(any(), any(), any())).thenReturn(Future(Some(monthlyReturn)))
      when(mockFileUploadService.uploadFileToObjectStore(any(), any(), any(), any(), any()))
        .thenReturn(Future("test/test"))

      when(
        mockMonthlyReturnService.storeSubmission(
          any(),
          any(),
          any(),
          any()
        )
      ).thenReturn(Future.successful(SubmitReturnResult.NotUpdatedInRepository))

      val result = controller.storeSubmission(lowercaseTestZReference, testTaxYear, testMonth)(
        FakeRequest("POST", submissionsPath)
          .withHeaders("Content-Type" -> "application/x-ndjson")
          .withBody(testTempFile)
      )

      status(result) mustBe SERVICE_UNAVAILABLE
      contentAsString(result) must include("Mongo error")
    }

    "must return ServiceUnavailable when ObjectStore can't return the file location" in {
      when(mockUuidGenerator.randomUuid()).thenReturn(UUID.fromString(testUploadReference))

      when(mockMonthlyReturnService.get(any(), any(), any())).thenReturn(Future(Some(monthlyReturn)))
      when(mockFileUploadService.uploadFileToObjectStore(any(), any(), any(), any(), any())).thenReturn(Future(None))

      when(
        mockMonthlyReturnService.storeSubmission(
          any(),
          any(),
          any(),
          any()
        )
      ).thenReturn(Future.successful(SubmitReturnResult.NotUpdatedInRepository))

      val result = controller.storeSubmission(lowercaseTestZReference, testTaxYear, testMonth)(
        FakeRequest("POST", submissionsPath)
          .withHeaders("Content-Type" -> "application/x-ndjson")
          .withBody(testTempFile)
      )

      status(result) mustBe SERVICE_UNAVAILABLE
    }

    "must return Not Found when the MonthlyReturn is uploaded" in {
      when(mockUuidGenerator.randomUuid()).thenReturn(UUID.fromString(testUploadReference))

      when(mockMonthlyReturnService.get(any(), any(), any())).thenReturn(Future(None))
      when(mockFileUploadService.uploadFileToObjectStore(any(), any(), any(), any(), any()))
        .thenReturn(Future("test/test"))

      when(
        mockMonthlyReturnService.storeSubmission(
          any(),
          any(),
          any(),
          any()
        )
      )
        .thenReturn(Future.successful(SubmitReturnResult.MonthlyReturnNotFound))

      val result = controller.storeSubmission(lowercaseTestZReference, testTaxYear, testMonth)(
        FakeRequest("POST", submissionsPath)
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
          any()
        )
      )
        .thenReturn(Future.successful(SubmitReturnResult.NoBody))

      val result = controller.storeSubmission(lowercaseTestZReference, testTaxYear, testMonth)(
        FakeRequest("POST", submissionsPath)
          .withHeaders("Content-Type" -> "application/x-ndjson")
      )

      status(result) mustBe BAD_REQUEST
    }
  }
}
