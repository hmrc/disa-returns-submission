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
import uk.gov.hmrc.disareturnssubmission.models.{MonthlyReturn, SubmissionDetails}
import uk.gov.hmrc.disareturnssubmission.repositories.MonthlyReturnRepository
import uk.gov.hmrc.disareturnssubmission.services.{ObjectStoreService, SubmitReturnResult}
import uk.gov.hmrc.disareturnssubmission.utils.{Md5Base64, UuidGenerator}

import java.nio.file.{Files, Path}
import java.time.{Clock, Instant}
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future, blocking}

class SubmissionStoreAction @Inject() (
  monthlyReturnRepository: MonthlyReturnRepository,
  objectStoreService: ObjectStoreService,
  uuidGenerator: UuidGenerator,
  clock: Clock,
  appConfig: AppConfig,
  val actorSystem: ActorSystem
)(implicit ec: ExecutionContext)
    extends Logging
    with Md5Base64 {

  private implicit val blockingExecutionContext: ExecutionContextExecutor =
    actorSystem.dispatchers.lookup("contexts.file-upload-blocking")

  def store(
    bodyPath: Path,
    monthlyReturn: MonthlyReturn
  ): Future[SubmitReturnResult] = {
    val fileNameOrReference = uuidGenerator.randomUuid()

    val someMd5 = Future {
      blocking {
        val length = Files.size(bodyPath)
        length -> checkMd5Base64(bodyPath)
      }
    }(blockingExecutionContext).flatMap {
      case (length, _) if length <= 0L => Future.successful(None)
      case (_, md5)                    => Future.successful(Some(md5))
    }(ec)

    someMd5.flatMap {
      case Some(md5) =>
        objectStoreService
          .uploadFileToObjectStore(fileNameOrReference.toString, bodyPath, appConfig.contentType, md5)
          .flatMap { fileLocation =>
            storeSubmission(
              fileNameOrReference.toString,
              bodyPath,
              fileLocation,
              md5.toString,
              monthlyReturn
            )
          }
      case None      => Future.successful(SubmitReturnResult.NoBody)
    }
  }

  private def storeSubmission(
    fileNameRef: String,
    filePath: Path,
    someLocation: String,
    checksum: String,
    monthlyReturn: MonthlyReturn
  ): Future[SubmitReturnResult] = {
    val fileUploadDetails = SubmissionDetails(
      fileNameRef,
      appConfig.contentType,
      checksum,
      filePath.toFile.length(),
      Some(someLocation)
    )

    val updatedMonthlyReturn = monthlyReturn.createFileUpload(fileNameRef, Instant.now(clock), fileUploadDetails)
    monthlyReturnRepository.upsert(updatedMonthlyReturn).flatMap {
      case true  => Future.successful(SubmitReturnResult.UpdateSuccessful)
      case false => Future.successful(SubmitReturnResult.NotUpdatedInRepository)
    }

  }
}
