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
import org.mockito.Mockito.{reset, when}
import play.api.test.FakeRequest
import play.api.test.Helpers.*
import uk.gov.hmrc.disareturnssubmission.repositories.MonthlyReturnRepository

import scala.concurrent.Future

class TestOnlyMonthlyReturnControllerSpec extends SpecBase {

  private val mockMonthlyReturnRepository = mock[MonthlyReturnRepository]
  private val controller                  = new TestOnlyMonthlyReturnController(stubControllerComponents(), mockMonthlyReturnRepository)

  "TestOnlyMonthlyReturnController" - {

    "must delete all monthly returns" in {
      reset(mockMonthlyReturnRepository)
      when(mockMonthlyReturnRepository.deleteAll()).thenReturn(Future.successful(2L))

      val result = controller.deleteAll()(FakeRequest("DELETE", "/test-only/monthly-returns"))

      status(result) mustBe NO_CONTENT
    }

    "must return ServiceUnavailable when deletion fails" in {
      reset(mockMonthlyReturnRepository)
      when(mockMonthlyReturnRepository.deleteAll()).thenReturn(Future.failed(new RuntimeException("boom")))

      val result = controller.deleteAll()(FakeRequest("DELETE", "/test-only/monthly-returns"))

      status(result) mustBe SERVICE_UNAVAILABLE
    }
  }
}
