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

package uk.gov.hmrc.disareturnssubmission.actions

import base.SpecBase
import org.apache.pekko.actor.ActorSystem
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.BeforeAndAfterEach
import uk.gov.hmrc.disareturnssubmission.config.AppConfig
import uk.gov.hmrc.disareturnssubmission.repositories.MonthlyReturnRepository
import uk.gov.hmrc.disareturnssubmission.services.{MonthlyReturnService, ObjectStoreService, SubmitReturnResult}
import uk.gov.hmrc.disareturnssubmission.utils.UuidGenerator

import java.time.{Clock, Instant, ZoneOffset}
import java.util.UUID
import scala.concurrent.Future
import scala.language.postfixOps

class SubmissionStoreActionSpec extends SpecBase with BeforeAndAfterEach {

  private val mockMonthlyReturnService    = mock[MonthlyReturnService]
  private val mockMonthlyReturnRepository = mock[MonthlyReturnRepository]
  private val mockObjectStoreService      = mock[ObjectStoreService]
  private val mockUuidGenerator           = mock[UuidGenerator]
  private val fixedNow: Instant           = testCreatedOn
  private val fixedClock: Clock           = Clock.fixed(fixedNow, ZoneOffset.UTC)
  private val appConfig                   = inject[AppConfig]

  val action = new SubmissionStoreAction(
    mockMonthlyReturnRepository,
    mockObjectStoreService,
    mockUuidGenerator,
    fixedClock,
    appConfig,
    inject[ActorSystem]
  )

  "SubmissionStoreAction" - {
    "must return UpdateSuccessful when successfully storing in Mongo" in {
      when(mockUuidGenerator.randomUuid()).thenReturn(UUID.fromString(testUploadReference))
      when(mockMonthlyReturnService.get(any(), any(), any())).thenReturn(Future(Some(monthlyReturn)))

      when(mockObjectStoreService.uploadFileToObjectStore(any(), any(), any(), any(), any()))
        .thenReturn(Future("test/test"))

      when(mockMonthlyReturnRepository.upsert(any()))
        .thenReturn(Future.successful(true))

      val result = action.store(testTempFile, monthlyReturn)

      result.futureValue mustBe SubmitReturnResult.UpdateSuccessful
    }

    "must return NotUpdatedInRepository when unable to store in Mongo" in {
      when(mockUuidGenerator.randomUuid()).thenReturn(UUID.fromString(testUploadReference))
      when(mockMonthlyReturnService.get(any(), any(), any())).thenReturn(Future(Some(monthlyReturn)))

      when(mockObjectStoreService.uploadFileToObjectStore(any(), any(), any(), any(), any()))
        .thenReturn(Future("test/test"))

      when(mockMonthlyReturnRepository.upsert(any()))
        .thenReturn(Future.successful(false))

      val result = action.store(testTempFile, monthlyReturn)

      result.futureValue mustBe SubmitReturnResult.NotUpdatedInRepository
    }
  }
}
