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

import org.apache.pekko.stream.Materializer
import play.api.Logging
import uk.gov.hmrc.disareturnssubmission.config.AppConfig
import uk.gov.hmrc.disareturnssubmission.models.SubmissionDetails
import uk.gov.hmrc.disareturnssubmission.repositories.MonthlyReturnRepository
import uk.gov.hmrc.disareturnssubmission.services.{ObjectStoreService, SubmitReturnResult}
import uk.gov.hmrc.disareturnssubmission.utils.{Md5Base64, UuidGenerator}

import java.nio.file.Path
import java.time.{Clock, Instant}
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class SubmissionStoreAction @Inject() (
  monthlyReturnRepository: MonthlyReturnRepository,
  objectStoreService: ObjectStoreService,
  uuidGenerator: UuidGenerator,
  clock: Clock,
  appConfig: AppConfig,
  implicit val mat: Materializer
)(implicit ec: ExecutionContext)
    extends Logging
    with Md5Base64 {

  def store(zReference: String, taxYear: String, month: Int, bodyPath: Path): Future[SubmitReturnResult] = {

    val md5                 = checkMd5Base64(bodyPath)
    val fileNameOrReference = uuidGenerator.randomUuid()
    objectStoreService
      .uploadFileToObjectStore(fileNameOrReference.toString, bodyPath, appConfig.contentType, md5)
      .flatMap { fileLocation =>
        storeSubmission(
          zReference,
          taxYear,
          month,
          fileNameOrReference.toString,
          bodyPath,
          fileLocation,
          md5.toString
        )
      }
  }

  private def storeSubmission(
    zReference: String,
    taxYear: String,
    month: Int,
    fileNameRef: String,
    filePath: Path,
    someLocation: String,
    checksum: String
  ): Future[SubmitReturnResult] = {
    val toUpdate          = monthlyReturnRepository.get(zReference, taxYear, month)
    val fileUploadDetails = SubmissionDetails(
      fileNameRef,
      appConfig.contentType,
      checksum,
      filePath.toFile.length(),
      Some(someLocation)
    )
    toUpdate.flatMap {

      case Some(monthlyReturn) =>

        val updatedMonthlyReturn = monthlyReturn.createFileUpload(fileNameRef, Instant.now(clock), fileUploadDetails)

        monthlyReturnRepository.upsert(updatedMonthlyReturn).flatMap {
          case true  => Future.successful(SubmitReturnResult.UpdateSuccessful)
          case false => Future.successful(SubmitReturnResult.NotUpdatedInRepository)
        }

      case _ => Future.successful(SubmitReturnResult.MonthlyReturnNotFound)
    }

  }
}
