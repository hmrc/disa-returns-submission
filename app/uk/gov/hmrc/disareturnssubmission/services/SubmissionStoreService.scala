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

package uk.gov.hmrc.disareturnssubmission.services

import org.apache.pekko.stream.Materializer
import play.api.Logging
import uk.gov.hmrc.disareturnssubmission.config.AppConfig
import uk.gov.hmrc.disareturnssubmission.utils.{Md5Base64, UuidGenerator}

import java.nio.file.Path
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class SubmissionStoreService @Inject() (
  monthlyReturnService: MonthlyReturnService,
  objectStoreService: ObjectStoreService,
  uuidGenerator: UuidGenerator,
  appConfig: AppConfig,
  implicit val mat: Materializer
)(implicit ec: ExecutionContext)
    extends Logging
    with Md5Base64 {

  def storeSubmission(zReference: String, taxYear: String, month: Int, bodyPath: Path): Future[SubmitReturnResult] =

    monthlyReturnService.get(zReference, taxYear, month).flatMap {
      case Some(value) => store(zReference, taxYear, month, bodyPath)
      case _           => Future.successful(SubmitReturnResult.MonthlyReturnNotFound)
    }

  private def store(zReference: String, taxYear: String, month: Int, bodyPath: Path): Future[SubmitReturnResult] = {

    val md5                 = checkMd5Base64(bodyPath)
    val fileNameOrReference = uuidGenerator.randomUuid()
    objectStoreService
      .uploadFileToObjectStore(fileNameOrReference.toString, bodyPath, appConfig.contentType, md5)
      .flatMap { fileLocation =>
        monthlyReturnService
          .storeSubmission(
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
}
