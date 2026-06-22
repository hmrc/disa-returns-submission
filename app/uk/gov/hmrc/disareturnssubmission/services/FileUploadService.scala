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

import play.api.Logging
import play.api.libs.Files.TemporaryFileCreator
import uk.gov.hmrc.disareturnssubmission.connectors.ObjectStoreConnector
import uk.gov.hmrc.disareturnssubmission.utils.TempFileSupport
import uk.gov.hmrc.http.HeaderCarrier

import java.nio.file.Path
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class FileUploadService @Inject (
  override protected val temporaryFileCreator: TemporaryFileCreator,
  objectStoreConnector: ObjectStoreConnector
)(implicit ec: ExecutionContext)
    extends Logging
    with TempFileSupport {

  private def serviceName: String = "disa-returns-submission"

  def uploadFileToObjectStore(
    fileName: String,
    filePath: Path,
    fileType: String
  ): Future[Option[String]] = {
    implicit val hc: HeaderCarrier = HeaderCarrier()
    logger.info(s"${logPrefix(fileName)} Original file object-store upload started for $fileName")
    objectStoreConnector
      .putFile(
        objectName = fileName,
        file = filePath,
        contentType = fileType
      )
      .map { location =>
        logger.info(
          s"${logPrefix(fileName)} Original file object-store upload completed for $fileName, object-store location [$location]"
        )
        Some(location)
      }
  }

  private def logPrefix(fileUploadReference: String): String =
    s"[$serviceName][process][fileUploadReference=$fileUploadReference]"

}
