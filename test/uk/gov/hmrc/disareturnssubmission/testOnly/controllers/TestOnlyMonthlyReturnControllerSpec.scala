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

import base.SpecBase
import org.mockito.Mockito.{reset, verify, verifyNoInteractions, when}
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers.*
import uk.gov.hmrc.disareturnssubmission.repositories.MonthlyReturnRepository

import scala.concurrent.Future

class TestOnlyMonthlyReturnControllerSpec extends SpecBase {

  private val mockMonthlyReturnRepository = mock[MonthlyReturnRepository]
  private val controller                  = new TestOnlyMonthlyReturnController(stubControllerComponents(), mockMonthlyReturnRepository)

  "TestOnlyMonthlyReturnController" - {

    "must delete monthly returns for normalized Z-references" in {
      reset(mockMonthlyReturnRepository)
      val otherZReference = "Z5678"
      when(mockMonthlyReturnRepository.deleteByZReferences(Seq(testZReference, otherZReference)))
        .thenReturn(Future.successful(2L))

      val result = controller.delete()(
        FakeRequest("POST", "/test-only/monthly-returns")
          .withHeaders("Content-Type" -> "application/json")
          .withBody(
            Json.obj(
              "zReferences" -> Seq(testZReference.toLowerCase, otherZReference)
            )
          )
      )

      status(result) mustBe NO_CONTENT
      verify(mockMonthlyReturnRepository).deleteByZReferences(Seq(testZReference, otherZReference))
    }

    "must reject a request containing an invalid Z-reference" in {
      reset(mockMonthlyReturnRepository)
      val result = controller.delete()(
        FakeRequest("POST", "/test-only/monthly-returns")
          .withHeaders("Content-Type" -> "application/json")
          .withBody(
            Json.obj(
              "zReferences" -> Seq(testZReference, "invalid")
            )
          )
      )

      status(result) mustBe BAD_REQUEST
      verifyNoInteractions(mockMonthlyReturnRepository)
    }

    "must reject an empty sequence of Z-references" in {
      reset(mockMonthlyReturnRepository)
      val result = controller.delete()(
        FakeRequest("POST", "/test-only/monthly-returns")
          .withHeaders("Content-Type" -> "application/json")
          .withBody(
            Json.obj(
              "zReferences" -> Seq.empty[String]
            )
          )
      )

      status(result) mustBe BAD_REQUEST
      verifyNoInteractions(mockMonthlyReturnRepository)
    }

    "must return ServiceUnavailable when scoped deletion fails" in {
      reset(mockMonthlyReturnRepository)
      when(mockMonthlyReturnRepository.deleteByZReferences(Seq(testZReference)))
        .thenReturn(Future.failed(new RuntimeException("boom")))

      val result = controller.delete()(
        FakeRequest("POST", "/test-only/monthly-returns")
          .withHeaders("Content-Type" -> "application/json")
          .withBody(
            Json.obj(
              "zReferences" -> Seq(testZReference)
            )
          )
      )

      status(result) mustBe SERVICE_UNAVAILABLE
    }

  }
}
