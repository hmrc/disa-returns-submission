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
import uk.gov.hmrc.disareturnssubmission.config.AppConfig
import uk.gov.hmrc.disareturnssubmission.connectors.*
import uk.gov.hmrc.disareturnssubmission.converters.MonthlyReturnNdJsonToNpsSubmissionConverter
import uk.gov.hmrc.disareturnssubmission.models.monthly.*
import uk.gov.hmrc.disareturnssubmission.repositories.MonthlyReturnRepository
import uk.gov.hmrc.disareturnssubmission.services.MonthlyReturnSubmissionProcessingService
import uk.gov.hmrc.play.bootstrap.tools.LogCapturing

import java.nio.file.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.*
import scala.jdk.CollectionConverters.*

class MonthlyReturnSubmissionProcessingServiceSpec extends SpecBase with LogCapturing {
  private val item                     = MonthlyReturnSubmissionWorkItem(testZReference, testTaxYear, testMonth, testUploadReference)
  private val context                  =
    s"zReference [$testZReference], taxYear [$testTaxYear], month [$testMonth], submission [$testUploadReference]"
  private val submissionSuccessMessage = "Submitted to NPS and marked"
  private val updateFailureMessage     = s"All NPS batches accepted but could not mark [NPS_SUBMITTED] for $context"
  private val singleRecordBatchLimit   = 1
  private val jsonBatchFileMarker      = "-json"
  private val stored                   = monthlyReturn.copy(submissions =
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
        Files.exists(createdPaths.find(_.getFileName.toString.contains(jsonBatchFileMarker)).value) mustBe true
        invocation
          .getArgument[Source[ByteString, ?]](1)
          .runWith(Sink.fold(ByteString.empty)(_ ++ _))
          .map { bytes => payload.success(bytes); () }
      }

      withCaptureOfLoggingFrom(Logger(service.getClass)) { events =>
        await(service.process(item)) mustBe (())

        val messages = events.map(_.getMessage)
        messages.head     must include(s"Starting NPS transfer for $context")
        messages.last     must include(s"$submissionSuccessMessage [NPS_SUBMITTED] for $context in [")
        events.filter(_.getLevel == INFO).size mustBe 2
        events.filter(_.getLevel == DEBUG).map(_.getMessage) mustBe List(
          s"[MonthlyReturnSubmissionProcessingService][transfer] Downloading from Object Store for $context",
          s"[MonthlyReturnSubmissionProcessingService][transfer] Downloaded [${ByteString(ndjsonContent).size}] bytes - converting NDJSON to NPS JSON for $context",
          s"[MonthlyReturnSubmissionProcessingService][transfer] Conversion completed with [1] batches for $context",
          s"[MonthlyReturnSubmissionProcessingService][sendSubmissionToNpsAndMarkSubmitted] Sending batch [1/1] with [2] records to NPS for $context",
          s"[MonthlyReturnSubmissionProcessingService][sendSubmissionToNpsAndMarkSubmitted] NPS accepted batch [1/1] for $context",
          s"[MonthlyReturnSubmissionProcessingService][sendSubmissionToNpsAndMarkSubmitted] All [1] NPS batches accepted - marking [NPS_SUBMITTED] for $context"
        )
        messages.mkString must not include "AB123456C"
        messages.mkString must not include "ACC123"
      }

      Json.parse(await(payload.future).utf8String) mustBe npsSubmissionJson
      verify(objectStore).getFile(eqTo(item.reference))(any())
      verify(repository).markSubmissionNpsSubmitted(item.zReference, item.taxYear, item.month, item.reference)
      assertCleaned()
    }

    "must send batches sequentially, delete accepted files early and mark submitted only after every batch succeeds" in new TestSetup {
      when(config.monthlyReturnMaxRecordsPerSubmission).thenReturn(singleRecordBatchLimit)
      val batchCount = npsSubmissionJson.value.size
      val payloads   = Seq.fill(batchCount)(Promise[ByteString]())
      val accepted   = Seq.fill(batchCount)(Promise[Unit]())
      val calls      = new AtomicInteger()
      when(nps.sendSubmission(any(), any())).thenAnswer { (invocation: InvocationOnMock) =>
        val index = calls.getAndIncrement()
        invocation
          .getArgument[Source[ByteString, ?]](1)
          .runWith(Sink.fold(ByteString.empty)(_ ++ _))
          .flatMap { bytes => payloads(index).success(bytes); accepted(index).future }
      }

      val result  = service.process(item)
      Json.parse(await(payloads.head.future).utf8String) mustBe Json.arr(npsSubmissionJson.value.head)
      val batches = paths.asScala.filter(_.getFileName.toString.contains(jsonBatchFileMarker)).toList
      batches.size mustBe batchCount
      batches.foreach(path => Files.exists(path) mustBe true)
      verify(nps, times(1)).sendSubmission(any(), any())
      verify(repository, never()).markSubmissionNpsSubmitted(any(), any(), any(), any())
      result.isCompleted mustBe false

      accepted.head.success(())
      Json.parse(await(payloads(1).future).utf8String) mustBe Json.arr(npsSubmissionJson.value(1))
      Files.exists(batches.head) mustBe false
      Files.exists(batches(1)) mustBe true
      verify(repository, never()).markSubmissionNpsSubmitted(any(), any(), any(), any())
      result.isCompleted mustBe false

      accepted(1).success(())
      await(result) mustBe (())
      verify(repository).markSubmissionNpsSubmitted(item.zReference, item.taxYear, item.month, item.reference)
      assertCleaned()
    }

    "must stop after a failed batch, clean unprocessed files and replay from the first batch on retry" in new TestSetup {
      when(config.monthlyReturnMaxRecordsPerSubmission).thenReturn(singleRecordBatchLimit)
      val thirdRecord = ndjsonContent.linesIterator.next()
      when(objectStore.getFile(any())(any()))
        .thenReturn(Future.successful(Source.single(ByteString(ndjsonContent + thirdRecord))))
      val payloads    = new ConcurrentLinkedQueue[ByteString]()
      val failure     = new IllegalStateException("second batch failed")
      when(nps.sendSubmission(any(), any())).thenAnswer { (invocation: InvocationOnMock) =>
        invocation
          .getArgument[Source[ByteString, ?]](1)
          .runWith(Sink.fold(ByteString.empty)(_ ++ _))
          .flatMap { bytes =>
            payloads.add(bytes)
            if (payloads.size() == 2) Future.failed(failure) else Future.unit
          }
      }

      await(service.process(item).failed) mustBe failure
      verify(nps, times(2)).sendSubmission(any(), any())
      verify(repository, never()).markSubmissionNpsSubmitted(any(), any(), any(), any())
      paths.size() mustBe 4
      assertCleaned()

      await(service.process(item)) mustBe (())
      payloads.asScala.toSeq.map(bytes => Json.parse(bytes.utf8String)) mustBe Seq(0, 1, 0, 1, 0).map(index =>
        Json.arr(npsSubmissionJson.value(index))
      )
      verify(objectStore, times(2)).getFile(eqTo(item.reference))(any())
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
        events.last.getMessage must include(s"already submitted to NPS - skipping transfer for $context")
        events.exists(_.getMessage.contains(submissionSuccessMessage)) mustBe false
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
          events.exists(_.getMessage.contains(submissionSuccessMessage)) mustBe false
        }
        verifyNoInteractions(objectStore, nps, creator)
      }
    }

    "must clean up the source and not create batches if the download stream fails" in new TestSetup {
      val failureMessage = "download failed"
      when(objectStore.getFile(any())(any())).thenReturn(
        Future.successful(
          Source.single(ByteString(ndjsonContent)).concat(Source.failed(new IllegalStateException(failureMessage)))
        )
      )

      withCaptureOfLoggingFrom(Logger(service.getClass)) { events =>
        await(service.process(item).failed).getMessage must include(failureMessage)
        events.last.getMessage                         must include(s"Downloading from Object Store for $context")
      }
      paths.size() mustBe 1
      assertCleaned()
      verifyNoInteractions(nps)
    }

    "must clean up and not submit if conversion throws" in new TestSetup {
      override lazy val converter = mock[MonthlyReturnNdJsonToNpsSubmissionConverter]
      val failureMessage          = "conversion failed"
      doThrow(new IllegalStateException(failureMessage)).when(converter).convert(any(), anyInt(), any())

      withCaptureOfLoggingFrom(Logger(service.getClass)) { events =>
        await(service.process(item).failed).getMessage mustBe failureMessage
        events.last.getMessage must include(s"converting NDJSON to NPS JSON for $context")
        events.exists(_.getMessage.contains(submissionSuccessMessage)) mustBe false
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

    "must clean up completed and partial batches without sending when a later record does not match the model" in new TestSetup {
      when(config.monthlyReturnMaxRecordsPerSubmission).thenReturn(singleRecordBatchLimit)
      when(objectStore.getFile(any())(any()))
        .thenReturn(Future.successful(Source.single(ByteString(ndjsonContent + "{\"id\":1}\n"))))

      await(service.process(item).failed).getMessage must include("line 3")
      paths.size() mustBe 4
      assertCleaned()
      verifyNoInteractions(nps)
      verify(repository, never()).markSubmissionNpsSubmitted(any(), any(), any(), any())
    }

    "must clean up without marking submitted when NPS fails" in new TestSetup {
      val failureMessage = "NPS unavailable"
      when(nps.sendSubmission(any(), any())).thenReturn(Future.failed(new RuntimeException(failureMessage)))

      withCaptureOfLoggingFrom(Logger(service.getClass)) { events =>
        await(service.process(item).failed).getMessage mustBe failureMessage
        events.last.getMessage must include(s"Sending batch [1/1] with [2] records to NPS for $context")
        events.exists(_.getMessage.contains(submissionSuccessMessage)) mustBe false
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
        events.exists(_.getMessage.contains(submissionSuccessMessage)) mustBe false
        updated.success(false)

        await(result.failed).getMessage must include("Could not mark submission")
        events.last.getLevel mustBe WARN
        events.last.getMessage          must include(updateFailureMessage)
        events.last.getMessage          must include("transfer may be retried")
        events.exists(_.getMessage.contains(submissionSuccessMessage)) mustBe false
      }
      assertCleaned()
    }

    "must warn without logging success if the NPS_SUBMITTED update fails" in new TestSetup {
      val failure = new IllegalStateException("mongo unavailable")
      when(repository.markSubmissionNpsSubmitted(any(), any(), any(), any())).thenReturn(Future.failed(failure))

      withCaptureOfLoggingFrom(Logger(service.getClass)) { events =>
        await(service.process(item).failed) mustBe failure
        events.last.getLevel mustBe WARN
        events.last.getMessage must include(updateFailureMessage)
        events.exists(_.getMessage.contains(submissionSuccessMessage)) mustBe false
      }
      assertCleaned()
    }

    Seq(1, 2).foreach { failingBatch =>
      s"must clean up the source and earlier batches if creating batch $failingBatch throws" in new TestSetup {
        val failureMessage = "disk unavailable"
        when(config.monthlyReturnMaxRecordsPerSubmission).thenReturn(singleRecordBatchLimit)
        doAnswer { (invocation: InvocationOnMock) =>
          if (paths.size() == failingBatch) throw new IllegalStateException(failureMessage)
          createFile(invocation.getArgument[String](0))
        }.when(creator).create(any[String], any[String])

        await(service.process(item).failed).getMessage mustBe failureMessage
        paths.size() mustBe failingBatch
        assertCleaned()
        verify(objectStore).getFile(eqTo(item.reference))(any())
        verifyNoInteractions(nps)
        verify(repository, never()).markSubmissionNpsSubmitted(any(), any(), any(), any())
      }
    }
  }

  private trait TestSetup {
    val repository     = mock[MonthlyReturnRepository]
    val objectStore    = mock[ObjectStoreConnector]
    val nps            = mock[NpsConnector]
    val creator        = mock[TemporaryFileCreator]
    val config         = mock[AppConfig]
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
    when(config.monthlyReturnMaxRecordsPerSubmission).thenReturn(3000)

    lazy val service = new MonthlyReturnSubmissionProcessingService(
      inject[ActorSystem],
      creator,
      converter,
      objectStore,
      nps,
      repository,
      config
    )

    def assertCleaned(): Unit = paths.asScala.foreach(path => Files.exists(path) mustBe false)
  }
}
