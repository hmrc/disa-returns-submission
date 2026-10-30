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

package uk.gov.hmrc.disareturnssubmission.service

import base.SpecBase
import ch.qos.logback.classic.Level.*
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.*
import org.apache.pekko.util.ByteString
import org.mockito.ArgumentMatchers.{eq as eqTo, *}
import org.mockito.Mockito.{timeout as mockitoTimeout, *}
import org.mockito.invocation.InvocationOnMock
import play.api.Logger
import play.api.libs.Files.*
import play.api.libs.json.Json
import play.api.test.Helpers.*
import uk.gov.hmrc.disareturnssubmission.connectors.*
import uk.gov.hmrc.disareturnssubmission.converters.MonthlyReturnNdJsonToNpsSubmissionConverter
import uk.gov.hmrc.disareturnssubmission.models.monthly.*
import uk.gov.hmrc.disareturnssubmission.repositories.MonthlyReturnRepository
import uk.gov.hmrc.disareturnssubmission.services.MonthlyReturnSubmissionProcessingService
import uk.gov.hmrc.play.bootstrap.tools.LogCapturing

import java.nio.file.*
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.*
import scala.jdk.CollectionConverters.*

class MonthlyReturnSubmissionProcessingServiceSpec extends SpecBase with LogCapturing {
  private val item    = MonthlyReturnSubmissionWorkItem(testZReference, testTaxYear, testMonth, testUploadReference)
  private val context =
    s"zReference [$testZReference], taxYear [$testTaxYear], month [$testMonth], submission [$testUploadReference]"
  private val stored  = monthlyReturn.copy(submissions =
    List(
      MonthlyReturnSubmission(
        testUploadReference,
        MonthlyReturnSubmissionStatus.Stored,
        testCreatedOn,
        Some(fileUploadDetails)
      )
    )
  )

  "MonthlyReturnSubmissionProcessingService" - {
    "must download, convert and stream JSON after deleting the source, then clean up the output" in new TestSetup {
      val payload = Promise[ByteString]()
      when(nps.sendSubmission(any(), any())).thenAnswer { (invocation: InvocationOnMock) =>
        val createdPaths = paths.asScala.toList
        Files.exists(createdPaths.find(_.getFileName.toString.contains("ndjson")).value) mustBe false
        Files.exists(createdPaths.find(_.getFileName.toString.contains("-json")).value) mustBe true
        invocation
          .getArgument[Source[ByteString, ?]](1)
          .runWith(Sink.fold(ByteString.empty)(_ ++ _))
          .map { bytes => payload.success(bytes); () }
      }

      withCaptureOfLoggingFrom(Logger(service.getClass)) { events =>
        await(service.process(item)) mustBe (())

        val messages = events.map(_.getMessage)
        messages.head     must include(s"Starting NPS transfer for $context")
        messages.last     must include(s"Submitted to NPS and marked [NPS_SUBMITTED] for $context in [")
        events.filter(_.getLevel == INFO).size mustBe 2
        events.filter(_.getLevel == DEBUG).map(_.getMessage) mustBe List(
          s"[MonthlyReturnSubmissionProcessingService][transfer] Downloading from Object Store for $context",
          s"[MonthlyReturnSubmissionProcessingService][transfer] Downloaded [${ByteString(ndjsonContent).size}] bytes; converting NDJSON to NPS JSON for $context",
          s"[MonthlyReturnSubmissionProcessingService][transfer] Conversion completed for $context",
          s"[MonthlyReturnSubmissionProcessingService][sendSubmissionToNpsAndMarkSubmitted] Sending to NPS for $context",
          s"[MonthlyReturnSubmissionProcessingService][sendSubmissionToNpsAndMarkSubmitted] NPS accepted submission; marking [NPS_SUBMITTED] for $context"
        )
        messages.mkString must not include "AB123456C"
        messages.mkString must not include "ACC123"
      }

      Json.parse(await(payload.future).utf8String) mustBe npsSubmissionJson
      verify(objectStore).getFile(eqTo(item.reference))(any())
      verify(repository).markSubmissionNpsSubmitted(item.zReference, item.taxYear, item.month, item.reference)
      assertCleaned()
    }

    "must complete an already submitted item without downloading or creating temporary files" in new TestSetup {
      val record =
        stored.copy(submissions = stored.submissions.map(_.copy(status = MonthlyReturnSubmissionStatus.NpsSubmitted)))
      when(repository.get(any(), any(), any())).thenReturn(Future.successful(Some(record)))

      withCaptureOfLoggingFrom(Logger(service.getClass)) { events =>
        await(service.process(item)) mustBe (())
        events.last.getLevel mustBe INFO
        events.last.getMessage must include(s"already submitted to NPS; skipping transfer for $context")
        events.exists(_.getMessage.contains("Submitted to NPS and marked")) mustBe false
      }

      verifyNoInteractions(objectStore, nps, creator)
      verify(repository, never()).markSubmissionNpsSubmitted(any(), any(), any(), any())
    }

    Seq(
      "missing return"     -> None,
      "missing submission" -> Some(monthlyReturn),
      "created submission" -> Some(
        stored.copy(submissions = stored.submissions.map(_.copy(status = MonthlyReturnSubmissionStatus.Created)))
      ),
      "missing location"   -> Some(
        stored.copy(submissions =
          stored.submissions.map(
            _.copy(submissionDetails = Some(fileUploadDetails.copy(objectStoreFileLocation = None)))
          )
        )
      )
    ).foreach { (description, record) =>
      s"must fail without transferring for $description" in new TestSetup {
        when(repository.get(any(), any(), any())).thenReturn(Future.successful(record))

        withCaptureOfLoggingFrom(Logger(service.getClass)) { events =>
          await(service.process(item).failed) mustBe a[IllegalStateException]
          events.last.getLevel mustBe WARN
          val reason =
            if (record.isEmpty) "Monthly return not found" else "Submission missing or not ready for NPS transfer"
          events.last.getMessage must include(s"$reason for $context")
          events.exists(_.getMessage.contains("Submitted to NPS and marked")) mustBe false
        }
        verifyNoInteractions(objectStore, nps, creator)
      }
    }

    "must clean up both files and not submit if the download stream fails" in new TestSetup {
      when(objectStore.getFile(any())(any())).thenReturn(
        Future.successful(
          Source.single(ByteString(ndjsonContent)).concat(Source.failed(new IllegalStateException("download failed")))
        )
      )

      withCaptureOfLoggingFrom(Logger(service.getClass)) { events =>
        await(service.process(item).failed).getMessage must include("download failed")
        events.last.getMessage                         must include(s"Downloading from Object Store for $context")
      }
      assertCleaned()
      verifyNoInteractions(nps)
    }

    "must clean up and not submit if conversion throws" in new TestSetup {
      override lazy val converter = mock[MonthlyReturnNdJsonToNpsSubmissionConverter]
      doThrow(new IllegalStateException("conversion failed")).when(converter).convert(any(), any())

      withCaptureOfLoggingFrom(Logger(service.getClass)) { events =>
        await(service.process(item).failed).getMessage mustBe "conversion failed"
        events.last.getMessage must include(s"converting NDJSON to NPS JSON for $context")
        events.exists(_.getMessage.contains("Submitted to NPS and marked")) mustBe false
      }
      assertCleaned()
      verifyNoInteractions(nps)
    }

    "must clean up and not submit malformed JSON" in new TestSetup {
      when(objectStore.getFile(any())(any())).thenReturn(Future.successful(Source.single(ByteString("not JSON"))))

      await(service.process(item).failed) mustBe a[Exception]
      assertCleaned()
      verifyNoInteractions(nps)
    }

    "must clean up and never submit when a later record does not match the model" in new TestSetup {
      when(objectStore.getFile(any())(any()))
        .thenReturn(Future.successful(Source.single(ByteString(ndjsonContent + "{\"id\":1}\n"))))

      await(service.process(item).failed).getMessage must include("line 3")
      assertCleaned()
      verifyNoInteractions(nps)
      verify(repository, never()).markSubmissionNpsSubmitted(any(), any(), any(), any())
    }

    "must clean up without marking submitted when NPS fails" in new TestSetup {
      when(nps.sendSubmission(any(), any())).thenReturn(Future.failed(new RuntimeException("NPS unavailable")))

      withCaptureOfLoggingFrom(Logger(service.getClass)) { events =>
        await(service.process(item).failed).getMessage mustBe "NPS unavailable"
        events.last.getMessage must include(s"Sending to NPS for $context")
        events.exists(_.getMessage.contains("Submitted to NPS and marked")) mustBe false
      }
      assertCleaned()
      verify(repository, never()).markSubmissionNpsSubmitted(any(), any(), any(), any())
    }

    "must clean up if the submission cannot be marked NPS_SUBMITTED" in new TestSetup {
      val updated = Promise[Boolean]()
      when(repository.markSubmissionNpsSubmitted(any(), any(), any(), any())).thenReturn(updated.future)

      withCaptureOfLoggingFrom(Logger(service.getClass)) { events =>
        val result = service.process(item)
        verify(repository, mockitoTimeout(1000)).markSubmissionNpsSubmitted(any(), any(), any(), any())
        events.exists(_.getMessage.contains("Submitted to NPS and marked")) mustBe false
        updated.success(false)

        await(result.failed).getMessage must include("Could not mark submission")
        events.last.getLevel mustBe WARN
        events.last.getMessage          must include(s"NPS accepted submission but could not mark [NPS_SUBMITTED] for $context")
        events.last.getMessage          must include("transfer may be retried")
        events.exists(_.getMessage.contains("Submitted to NPS and marked")) mustBe false
      }
      assertCleaned()
    }

    "must warn without logging success if the NPS_SUBMITTED update fails" in new TestSetup {
      val failure = new IllegalStateException("mongo unavailable")
      when(repository.markSubmissionNpsSubmitted(any(), any(), any(), any())).thenReturn(Future.failed(failure))

      withCaptureOfLoggingFrom(Logger(service.getClass)) { events =>
        await(service.process(item).failed) mustBe failure
        events.last.getLevel mustBe WARN
        events.last.getMessage must include(s"NPS accepted submission but could not mark [NPS_SUBMITTED] for $context")
        events.exists(_.getMessage.contains("Submitted to NPS and marked")) mustBe false
      }
      assertCleaned()
    }

    "must clean up the first temporary file if creating the second throws" in new TestSetup {
      doAnswer { (invocation: InvocationOnMock) =>
        val prefix = invocation.getArgument[String](0)
        if (prefix.endsWith("ndjson")) throw new IllegalStateException("disk unavailable")
        createFile(prefix)
      }.when(creator).create(any[String], any[String])

      await(service.process(item).failed).getMessage mustBe "disk unavailable"
      paths.size() mustBe 1
      assertCleaned()
      verifyNoInteractions(objectStore, nps)
    }
  }

  private trait TestSetup {
    val repository     = mock[MonthlyReturnRepository]
    val objectStore    = mock[ObjectStoreConnector]
    val nps            = mock[NpsConnector]
    val creator        = mock[TemporaryFileCreator]
    val paths          = new ConcurrentLinkedQueue[Path]()
    lazy val converter = new MonthlyReturnNdJsonToNpsSubmissionConverter()

    def createFile(prefix: String): TemporaryFile = {
      val file = inject[TemporaryFileCreator].create(prefix)
      paths.add(file.path)
      file
    }

    when(creator.create(any[String], any[String])).thenAnswer { (invocation: InvocationOnMock) =>
      createFile(invocation.getArgument[String](0))
    }
    when(repository.get(any(), any(), any())).thenReturn(Future.successful(Some(stored)))
    when(repository.markSubmissionNpsSubmitted(any(), any(), any(), any())).thenReturn(Future.successful(true))
    when(objectStore.getFile(any())(any())).thenReturn(Future.successful(Source.single(ByteString(ndjsonContent))))
    when(nps.sendSubmission(any(), any())).thenReturn(Future.unit)

    lazy val service = new MonthlyReturnSubmissionProcessingService(
      inject[ActorSystem],
      creator,
      converter,
      objectStore,
      nps,
      repository
    )

    def assertCleaned(): Unit = paths.asScala.foreach(path => Files.exists(path) mustBe false)
  }
}
