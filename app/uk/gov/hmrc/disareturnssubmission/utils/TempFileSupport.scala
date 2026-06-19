package uk.gov.hmrc.disareturnssubmission.utils

import play.api.Logging
import play.api.libs.Files.{TemporaryFile, TemporaryFileCreator}

import java.nio.file.Path
import scala.concurrent.{ExecutionContext, Future}

trait TempFileSupport extends Logging {

  protected def temporaryFileCreator: TemporaryFileCreator

  protected def withTempFile[A](
    prefix: String
  )(use: Path => Future[A])(implicit ec: ExecutionContext): Future[A] = {
    val tempFile: TemporaryFile = temporaryFileCreator.create(prefix)

    use(tempFile.path).andThen { case _ =>
      try
        tempFile.delete()
      catch {
        case exception: Exception =>
          logger.warn(s"[TempFileSupport][withTempFile] Failed to delete temporary file: $tempFile", exception)
      }
    }(ec)
  }
}
