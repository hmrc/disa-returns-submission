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

import org.apache.pekko.actor.ActorSystem
import play.api.Logging
import uk.gov.hmrc.disareturnssubmission.config.AppConfig
import uk.gov.hmrc.disareturnssubmission.models.monthly.*
import uk.gov.hmrc.disareturnssubmission.repositories.*
import uk.gov.hmrc.disareturnssubmission.services.*
import uk.gov.hmrc.disareturnssubmission.utils.*

import java.nio.file.*
import javax.inject.Inject
import scala.concurrent.*

class MonthlyReturnSubmissionStoreAction @Inject() (
  monthlyReturnRepository: MonthlyReturnRepository,
  objectStoreService: ObjectStoreService,
  appConfig: AppConfig,
  ndjsonRecordCounter: NdjsonRecordCounter,
  val actorSystem: ActorSystem
)(implicit ec: ExecutionContext)
    extends Logging
    with Md5Base64 {

  private implicit val blockingExecutionContext: ExecutionContextExecutor =
    actorSystem.dispatchers.lookup("contexts.file-upload-blocking")

  def store(
    bodyPath: Path,
    monthlyReturn: MonthlyReturn,
    submissionId: String
  ): Future[SubmitReturnResult] = {
    val someMd5 = Future {
      blocking {
        val length = Files.size(bodyPath)
        (length, checkMd5Base64(bodyPath), ndjsonRecordCounter.count(bodyPath))
      }
    }(blockingExecutionContext).flatMap {
      case (length, _, _) if length <= 0L => Future.successful(None)
      case (length, md5, numberOfEntries) => Future.successful(Some((md5, length, numberOfEntries)))
    }(ec)

    someMd5.flatMap {
      case Some((md5, length, numberOfEntries)) =>
        objectStoreService
          .uploadFileToObjectStore(submissionId, bodyPath, appConfig.contentType, md5, length)
          .flatMap { fileLocation =>
            storeSubmission(
              submissionId,
              fileLocation,
              md5.toString,
              monthlyReturn,
              length,
              numberOfEntries
            )
          }
      case None                                 => Future.successful(SubmitReturnResult.NoBody)
    }
  }

  private def storeSubmission(
    fileNameRef: String,
    someLocation: String,
    checksum: String,
    monthlyReturn: MonthlyReturn,
    length: Long,
    numberOfEntries: Long
  ): Future[SubmitReturnResult] = {
    val fileUploadDetails = MonthlyReturnSubmissionDetails(
      fileNameRef,
      appConfig.contentType,
      checksum,
      length,
      numberOfEntries,
      Some(someLocation)
    )

    monthlyReturnRepository
      .storeSubmission(
        zReference = monthlyReturn.zReference,
        taxYear = monthlyReturn.taxYear,
        month = monthlyReturn.month,
        reference = fileNameRef,
        submissionDetails = fileUploadDetails
      )
      .map {
        case StoreSubmissionRepositoryResult.SubmissionStored      =>
          SubmitReturnResult.UpdateSuccessful
        case StoreSubmissionRepositoryResult.SubmissionConflict    =>
          SubmitReturnResult.SubmissionConflict
        case StoreSubmissionRepositoryResult.MonthlyReturnNotFound =>
          SubmitReturnResult.MonthlyReturnNotFound
      }
  }
}
