package uk.gov.hmrc.disareturnssubmission.services

import com.google.inject.Inject
import play.api.Logging
import uk.gov.hmrc.disareturnssubmission.models.common.{ErrorResponse, InternalServerErr}
import uk.gov.hmrc.disareturnssubmission.models.summary.NotificationContext
import uk.gov.hmrc.disareturnssubmission.mongoRepositories.NotificationContextRepository

import javax.inject.Singleton
import scala.concurrent.{ExecutionContext, Future}

class NotificationContextService @Inject() (repository: NotificationContextRepository)(implicit ec: ExecutionContext)
    extends Logging {

  def saveContext(clientId: String, boxId: Option[String], zReference: String): Future[Either[ErrorResponse, Unit]] =
    repository
      .insertNotificationContext(NotificationContext(clientId, boxId, zReference))
      .map(aNotification => Right(aNotification))
      .recover { case ex: Throwable =>
        logger.error(s"Failed to insertNotificationContext for zReference [$zReference]. Error: ${ex.getMessage}", ex)
        Left(InternalServerErr())
      }
  def retrieveContext(zReference: String): Future[Option[NotificationContext]]                                      =
    repository.findNotificationContext(zReference)

}
