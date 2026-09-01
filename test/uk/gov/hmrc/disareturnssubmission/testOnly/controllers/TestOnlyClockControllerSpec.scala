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
import org.mockito.Mockito.when
import play.api.test.FakeRequest
import play.api.test.Helpers.*
import uk.gov.hmrc.disareturnssubmission.testOnly.OverridableClock

import java.time.{Instant, LocalDate}
import scala.concurrent.Future

class TestOnlyClockControllerSpec extends SpecBase {

  private val clock      = mock[OverridableClock]
  private val controller = new TestOnlyClockController(stubControllerComponents(), clock)
  private val now        = Instant.parse("2026-05-20T00:00:00Z")

  "TestOnlyClockController" - {

    "must set the clock date" in {
      when(clock.setDate(testZReference, LocalDate.parse("2026-05-20"))).thenReturn(Future.unit)
      when(clock.instant(testZReference)).thenReturn(Future.successful(now))

      val result = controller.setDate(testZReference, "2026-05-20")(
        FakeRequest("PUT", s"/test-only/clock/$testZReference/2026-05-20")
      )

      status(result) mustBe OK
      (contentAsJson(result) \ "date").as[String] mustBe "2026-05-20"
    }

    "must reject an invalid date" in {
      val result = controller.setDate(testZReference, "20-05-2026")(
        FakeRequest("PUT", s"/test-only/clock/$testZReference/20-05-2026")
      )

      status(result) mustBe BAD_REQUEST
    }

    "must reset the clock" in {
      when(clock.reset(testZReference)).thenReturn(Future.unit)
      when(clock.instant(testZReference)).thenReturn(Future.successful(now))

      val result = controller.resetClock(testZReference)(FakeRequest("DELETE", s"/test-only/clock/$testZReference"))

      status(result) mustBe OK
      (contentAsJson(result) \ "date").as[String] mustBe "2026-05-20"
    }
  }
}
