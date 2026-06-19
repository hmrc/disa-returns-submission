package uk.gov.hmrc.disareturnssubmission.connectors

import uk.gov.hmrc.objectstore.client.play.PlayObjectStoreClient
import com.typesafe.config.Config
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.FileIO
import uk.gov.hmrc.disareturnssubmission.utils.Md5Base64
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.objectstore.client.http.Payload
import uk.gov.hmrc.objectstore.client.{Md5Hash, Path}

import java.nio.file.{Files, Path as FilePath}
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future, blocking}

class ObjectStoreConnector @Inject() (
  client: PlayObjectStoreClient,
  md5Base64: Md5Base64,
  override val actorSystem: ActorSystem,
  override val configuration: Config
)(implicit ec: ExecutionContext)
    extends BaseConnector {

  import uk.gov.hmrc.objectstore.client.play.Implicits.*

  private implicit val blockingExecutionContext: ExecutionContextExecutor =
    actorSystem.dispatchers.lookup("contexts.file-upload-blocking")

  def putFile(
    objectName: String,
    file: FilePath,
    contentType: String
  )(implicit hc: HeaderCarrier): Future[String] =
    Future {
      blocking {
        val length = Files.size(file)
        val md5    = md5Base64.checkMd5Base64(file)
        length -> md5
      }
    }(blockingExecutionContext).flatMap { case (length, md5) =>
      retryFor[String]("put object-store file")(retryCondition) {
        client
          .putObject(
            path = Path.Directory("").file(objectName),
            content = Payload(
              length = length,
              md5Hash = md5,
              content = FileIO.fromPath(file)
            ),
            contentType = Some(contentType)
          )
          .map(_.location.asUri)
      }
    }(ec)

}
