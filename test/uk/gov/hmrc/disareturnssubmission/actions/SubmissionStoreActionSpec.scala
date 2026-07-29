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
import org.mockito.ArgumentMatchers.{any, eq as eqTo}
import org.mockito.Mockito.{reset, verify, verifyNoInteractions, when}
import org.scalatest.BeforeAndAfterEach
import uk.gov.hmrc.disareturnssubmission.config.AppConfig
import uk.gov.hmrc.disareturnssubmission.repositories.{MonthlyReturnRepository, StoreSubmissionRepositoryResult}
import uk.gov.hmrc.disareturnssubmission.services.{ObjectStoreService, SubmitReturnResult}

import java.nio.file.Files
import scala.concurrent.Future
import scala.language.postfixOps

class SubmissionStoreActionSpec extends SpecBase with BeforeAndAfterEach {

  private val mockMonthlyReturnRepository = mock[MonthlyReturnRepository]
  private val mockObjectStoreService      = mock[ObjectStoreService]
  private val appConfig                   = inject[AppConfig]

  val action = new SubmissionStoreAction(
    mockMonthlyReturnRepository,
    mockObjectStoreService,
    appConfig,
    inject[ActorSystem]
  )

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    reset(mockMonthlyReturnRepository, mockObjectStoreService)
  }

  "SubmissionStoreAction" - {
    "must return UpdateSuccessful when successfully storing in Mongo" in {
      when(mockObjectStoreService.uploadFileToObjectStore(any(), any(), any(), any(), any()))
        .thenReturn(Future("test/test"))

      when(mockMonthlyReturnRepository.storeSubmission(any(), any(), any(), any(), any()))
        .thenReturn(Future.successful(StoreSubmissionRepositoryResult.SubmissionStored))

      val result = action.store(testTempFile, monthlyReturn, testUploadReference)

      result.futureValue mustBe SubmitReturnResult.UpdateSuccessful
      verify(mockObjectStoreService)
        .uploadFileToObjectStore(eqTo(testUploadReference), any(), any(), any(), any())
      verify(mockMonthlyReturnRepository)
        .storeSubmission(any(), any(), any(), eqTo(testUploadReference), any())
    }

    "must return SubmissionConflict when the repository reports a conflict" in {
      when(mockObjectStoreService.uploadFileToObjectStore(any(), any(), any(), any(), any()))
        .thenReturn(Future("test/test"))

      when(mockMonthlyReturnRepository.storeSubmission(any(), any(), any(), any(), any()))
        .thenReturn(Future.successful(StoreSubmissionRepositoryResult.SubmissionConflict))

      action
        .store(testTempFile, monthlyReturn, testUploadReference)
        .futureValue mustBe SubmitReturnResult.SubmissionConflict
    }

    "must return MonthlyReturnNotFound when the repository can no longer find the return" in {
      when(mockObjectStoreService.uploadFileToObjectStore(any(), any(), any(), any(), any()))
        .thenReturn(Future("test/test"))
      when(mockMonthlyReturnRepository.storeSubmission(any(), any(), any(), any(), any()))
        .thenReturn(Future.successful(StoreSubmissionRepositoryResult.MonthlyReturnNotFound))

      action
        .store(testTempFile, monthlyReturn, testUploadReference)
        .futureValue mustBe SubmitReturnResult.MonthlyReturnNotFound
    }

    "must return NoBody without calling object store or Mongo when the body is empty" in {
      val emptyFile = play.api.libs.Files.SingletonTemporaryFileCreator.create("empty", ".ndjson")
      Files.size(emptyFile.path) mustBe 0L

      action
        .store(emptyFile, monthlyReturn, testUploadReference)
        .futureValue mustBe SubmitReturnResult.NoBody

      verifyNoInteractions(mockObjectStoreService, mockMonthlyReturnRepository)
    }
  }
}
