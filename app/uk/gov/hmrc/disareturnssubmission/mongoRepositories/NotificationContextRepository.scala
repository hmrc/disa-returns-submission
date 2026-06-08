package uk.gov.hmrc.disareturnssubmission.mongoRepositories

import org.mongodb.scala.model._
import uk.gov.hmrc.disareturnssubmission.config.AppConfig
import uk.gov.hmrc.disareturnssubmission.models.summary.NotificationContext
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository

import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

class NotificationContextRepository @Inject() (mc: MongoComponent, appConfig: AppConfig)(implicit ec: ExecutionContext)
    extends PlayMongoRepository[NotificationContext](
      mongoComponent = mc,
      collectionName = "notificationContext",
      domainFormat = NotificationContext.mongoFormat,
      indexes = Seq(
        IndexModel(
          keys = Indexes.ascending("zReference"),
          indexOptions = IndexOptions().unique(true).name("notificationContextIdx")
        ),
        IndexModel(
          keys = Indexes.ascending("updatedAt"),
          indexOptions = IndexOptions()
            .name("updatedAtTtlIdx")
            .expireAfter(appConfig.timeToLive, TimeUnit.DAYS)
        )
      ),
      replaceIndexes = true
    ) {

  def findNotificationContext(zReference: String): Future[Option[NotificationContext]] =
    collection.find(Filters.eq("zReference", zReference)).headOption()

  def insertNotificationContext(notificationContext: NotificationContext): Future[Unit] =
    collection
      .replaceOne(
        filter = Filters.eq("zReference", notificationContext.zReference),
        replacement = notificationContext,
        options = ReplaceOptions().upsert(true)
      )
      .toFuture()
      .map(_ => ())
}
