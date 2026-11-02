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

import base.SpecBase
import ch.qos.logback.classic.Level.WARN
import com.typesafe.config.*
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.*
import org.apache.pekko.util.ByteString
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import play.api.Logger
import play.api.http.MimeTypes.TEXT
import play.api.http.Status.INTERNAL_SERVER_ERROR
import play.api.test.Helpers.*
import uk.gov.hmrc.disareturnssubmission.utils.Constants.NDJSON_MIME_TYPE
import uk.gov.hmrc.http.*
import uk.gov.hmrc.objectstore.client.http.Payload
import uk.gov.hmrc.objectstore.client.play.PlayObjectStoreClient
import uk.gov.hmrc.objectstore.client.{Object as StoredObject, *}
import uk.gov.hmrc.play.bootstrap.tools.LogCapturing

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Path as _, *}
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import scala.concurrent.Future

class ObjectStoreConnectorSpec extends SpecBase with LogCapturing {

  private implicit val hc: HeaderCarrier = HeaderCarrier()
  private val callAmountWithRetries: Int = 4
  private val objectName                 = "object-name"
  private val fileContent                = "file-content"
  private val uploadFailureMessage       = "object-store upload failed"
  private val retryConfig: Config        =
    ConfigFactory.parseString("http-verbs.retries.intervals = [1 millisecond, 1 millisecond, 1 millisecond]")

  "ObjectStoreConnector" - {

    "must download the file as a stream" in {
      val client    = mock[PlayObjectStoreClient]
      val connector = new ObjectStoreConnector(client, inject[ActorSystem], retryConfig)
      val content   = Source.single(ByteString(fileContent))
      val stored    = StoredObject(
        Path.Directory("").file(objectName),
        content,
        ObjectMetadata(NDJSON_MIME_TYPE, 12L, testMd5Hash, Instant.now(), Map.empty)
      )
      when(client.getObject[Source[ByteString, NotUsed]](any, any)(any, any))
        .thenReturn(Future.successful(Some(stored)))

      val source = await(connector.getFile(objectName))
      await(source.runWith(Sink.fold(ByteString.empty)(_ ++ _))(inject[Materializer])) mustBe ByteString(fileContent)
    }

    "must fail without retrying a missing object" in {
      val client    = mock[PlayObjectStoreClient]
      val connector = new ObjectStoreConnector(client, inject[ActorSystem], retryConfig)
      when(client.getObject[Source[ByteString, NotUsed]](any, any)(any, any)).thenReturn(Future.successful(None))

      await(connector.getFile("missing").failed) mustBe a[NoSuchElementException]
      verify(client).getObject[Source[ByteString, NotUsed]](any, any)(any, any)
    }

    "must retry object-store download 5xx failures" in {
      val client    = mock[PlayObjectStoreClient]
      val connector = new ObjectStoreConnector(client, inject[ActorSystem], retryConfig)
      when(client.getObject[Source[ByteString, NotUsed]](any, any)(any, any))
        .thenReturn(Future.failed(UpstreamErrorResponse("download failed", INTERNAL_SERVER_ERROR)))

      withCaptureOfLoggingFrom(Logger("application")) { events =>
        await(connector.getFile(objectName).failed) mustBe a[UpstreamErrorResponse]
        val retries = events.filter(_.getLevel == WARN)
        retries.size mustBe callAmountWithRetries - 1
        retries.foreach(_.getMessage must include("[ObjectStoreConnector][getFile]"))
      }
      verify(client, times(callAmountWithRetries)).getObject[Source[ByteString, NotUsed]](any, any)(any, any)
    }

    "must upload the file as a streaming Payload and return the object-store location" in {
      val client                                                        = mock[PlayObjectStoreClient]
      val file                                                          = Files.createTempFile("disa-object-store", ".txt")
      Files.writeString(file, fileContent)
      val summaryLocation                                               = Path.Directory("").file("uploaded-file")
      val payloadCaptor: ArgumentCaptor[Payload[Source[ByteString, ?]]] =
        ArgumentCaptor.forClass(classOf[Payload[Source[ByteString, ?]]])

      when(client.putObject(any, any[Payload[Source[ByteString, ?]]], any, any, any, any)(any, any))
        .thenReturn(
          Future.successful(
            ObjectSummaryWithMd5(summaryLocation, testFileLength, Md5Hash(md5Base64(fileContent)), Instant.now)
          )
        )

      try {
        val service = new ObjectStoreConnector(client, inject[ActorSystem], retryConfig)

        await(
          service
            .putFile(objectName, file, TEXT, testMd5Hash, testFileLength)
        ) mustBe summaryLocation.asUri

        verify(client).putObject(any, payloadCaptor.capture(), any, any, any, any)(any, any)
        val payload = payloadCaptor.getValue
        payload.length mustBe testFileLength
        payload.md5Hash mustBe Md5Hash(md5Base64(fileContent))
        await(
          payload.content
            .runWith(Sink.fold(ByteString.empty)(_ ++ _))(inject[Materializer])
        ) mustBe ByteString(fileContent)
      } finally Files.deleteIfExists(file)
    }

    "must return a failed Future, not throw eagerly, when file metadata cannot be read" in {
      val client      = mock[PlayObjectStoreClient]
      val service     = new ObjectStoreConnector(client, inject[ActorSystem], retryConfig)
      val missingFile = Files.createTempDirectory("disa-missing-parent").resolve("missing.txt")

      when(client.putObject(any, any[Payload[Source[ByteString, ?]]], any, any, any, any)(any, any))
        .thenReturn(Future.failed(UpstreamErrorResponse(uploadFailureMessage, INTERNAL_SERVER_ERROR)))

      val result = service.putFile(objectName, missingFile, TEXT, testMd5Hash, testFileLength)

      await(result.failed) mustBe a[NoSuchFileException]
      Files.deleteIfExists(missingFile.getParent)
    }

    "must retry when object-store returns a 5xx error" in {
      val client  = mock[PlayObjectStoreClient]
      val service = new ObjectStoreConnector(client, inject[ActorSystem], retryConfig)
      val file    = Files.createTempFile("disa-object-store", ".txt")
      Files.writeString(file, fileContent)

      when(client.putObject(any, any[Payload[Source[ByteString, ?]]], any, any, any, any)(any, any))
        .thenReturn(Future.failed(UpstreamErrorResponse(uploadFailureMessage, INTERNAL_SERVER_ERROR)))

      try {
        withCaptureOfLoggingFrom(Logger("application")) { events =>
          val result = await(service.putFile(objectName, file, TEXT, testMd5Hash, testFileLength).failed)

          result mustBe UpstreamErrorResponse(uploadFailureMessage, INTERNAL_SERVER_ERROR)
          val retries = events.filter(_.getLevel == WARN)
          retries.size mustBe callAmountWithRetries - 1
          retries.foreach(_.getMessage must include("[ObjectStoreConnector][putFile]"))
        }
        verify(client, times(callAmountWithRetries))
          .putObject(any, any[Payload[Source[ByteString, ?]]], any, any, any, any)(any, any)
      } finally Files.deleteIfExists(file)
    }
  }

  private def md5Base64(value: String): String = {
    val digest = MessageDigest.getInstance("MD5").digest(value.getBytes(UTF_8))
    Base64.getEncoder.encodeToString(digest)
  }
}
