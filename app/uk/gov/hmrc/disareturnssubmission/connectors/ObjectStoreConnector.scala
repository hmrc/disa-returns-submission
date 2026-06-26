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
import scala.concurrent.{ExecutionContextExecutor, Future}

class ObjectStoreConnector @Inject() (
  client: PlayObjectStoreClient,
  override val actorSystem: ActorSystem,
  override val configuration: Config
) extends BaseConnector
    with Md5Base64 {

  import uk.gov.hmrc.objectstore.client.play.Implicits.*

  private implicit val blockingExecutionContext: ExecutionContextExecutor =
    actorSystem.dispatchers.lookup("contexts.file-upload-blocking")

  def putFile(
    objectName: String,
    file: FilePath,
    contentType: String,
    md5: Md5Hash,
    length: Long
  )(implicit hc: HeaderCarrier): Future[String] =

    if (!Files.exists(file))
      Future.failed(new java.nio.file.NoSuchFileException(file.toString))
    else

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

}
