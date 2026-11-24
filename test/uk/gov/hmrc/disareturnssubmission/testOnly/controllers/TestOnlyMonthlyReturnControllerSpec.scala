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
import org.mockito.Mockito.{verifyNoInteractions, when}
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers.*
import uk.gov.hmrc.disareturnssubmission.repositories.*
import uk.gov.hmrc.disareturnssubmission.validators.StrictZReferenceValidator

import scala.concurrent.Future

class TestOnlyMonthlyReturnControllerSpec extends SpecBase {

  "TestOnlyMonthlyReturnController" - {

    "must leave returns alone when work-item cleanup fails" in {
      val returns      = mock[MonthlyReturnRepository]
      val declarations = mock[MonthlyReturnDeclarationWorkItemRepository]
      val submissions  = mock[MonthlyReturnSubmissionWorkItemRepository]
      val controller   = new TestOnlyMonthlyReturnController(
        stubControllerComponents(),
        returns,
        declarations,
        submissions,
        new StrictZReferenceValidator
      )

      val zReferences = Seq(testZReference)
      when(declarations.deleteByZReferences(zReferences)).thenReturn(Future.successful(1L))
      when(submissions.deleteByZReferences(zReferences)).thenReturn(Future.failed(new RuntimeException("boom")))

      val result = controller.cleanup()(
        FakeRequest(POST, "/test-only/monthly-returns/cleanup").withBody(Json.obj("zReferences" -> zReferences))
      )
      status(result) mustBe SERVICE_UNAVAILABLE
      verifyNoInteractions(returns)
    }
  }
}
