package uk.gov.hmrc.disareturnssubmission.services

import play.api.Logging
import play.api.libs.Files.TemporaryFileCreator
import uk.gov.hmrc.disareturnssubmission.connectors.ObjectStoreConnector
import uk.gov.hmrc.disareturnssubmission.utils.TempFileSupport
import uk.gov.hmrc.http.HeaderCarrier

import java.nio.file.Path
import scala.concurrent.{ExecutionContext, Future}

abstract class FileUploadService(
  override protected val temporaryFileCreator: TemporaryFileCreator,
  objectStoreConnector: ObjectStoreConnector
)(implicit ec: ExecutionContext)
    extends Logging
    with TempFileSupport {

  protected def serviceName: String

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
