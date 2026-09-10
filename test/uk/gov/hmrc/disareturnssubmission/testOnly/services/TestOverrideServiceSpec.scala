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

package uk.gov.hmrc.disareturnssubmission.testOnly.services

import base.SpecBase
import org.mockito.Mockito.{verify, when}
import uk.gov.hmrc.disareturnssubmission.testOnly.models.*
import uk.gov.hmrc.disareturnssubmission.testOnly.repositories.TestOverrideRepository

import java.time.Instant
import scala.concurrent.Future

class TestOverrideServiceSpec extends SpecBase {

  private val repository = mock[TestOverrideRepository]
  private val service    = new TestOverrideService(repository)

  "TestOverrideService" - {

    "must bulk replace overrides" in {
      val zReferences = Seq(testZReference, "Z5678")
      val request     = TestOverrideRequest(zReferences, None, None)
      when(repository.replace(zReferences, request)).thenReturn(Future.unit)

      service.replace(zReferences, request).futureValue

      verify(repository).replace(zReferences, request)
    }

    "must return empty options when no active aggregate exists" in {
      when(repository.getActive(testZReference)).thenReturn(Future.successful(None))

      service.get(testZReference).futureValue mustBe TestOverride(testZReference, None, None)
    }

    "must bulk delete overrides" in {
      val zReferences = Seq(testZReference, "Z5678")
      when(repository.delete(zReferences)).thenReturn(Future.unit)

      service.delete(zReferences).futureValue

      verify(repository).delete(zReferences)
    }

    "must return an active aggregate" in {
      val document = TestOverrideDocument(testZReference, None, None, Instant.MAX, Instant.EPOCH)
      when(repository.getActive(testZReference)).thenReturn(Future.successful(Some(document)))

      service.get(testZReference).futureValue mustBe TestOverride(testZReference, None, None)
    }
  }
}
