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

package uk.gov.hmrc.disareturnssubmission.jobs

import base.SpecBase
import ch.qos.logback.classic.Level.*
import org.apache.pekko.actor.ActorSystem
import org.bson.types.ObjectId
import org.mockito.ArgumentMatchers.{eq as eqTo, *}
import org.mockito.Mockito.{timeout as mockitoTimeout, *}
import play.api.Logger
import play.api.inject.ApplicationLifecycle
import play.api.test.Helpers.*
import uk.gov.hmrc.disareturnssubmission.config.AppConfig
import uk.gov.hmrc.disareturnssubmission.models.monthly.MonthlyReturnSubmissionWorkItem
import uk.gov.hmrc.disareturnssubmission.repositories.MonthlyReturnSubmissionWorkItemRepository
import uk.gov.hmrc.disareturnssubmission.services.MonthlyReturnSubmissionProcessingService
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.mongo.workitem.*
import uk.gov.hmrc.play.bootstrap.tools.LogCapturing

import java.time.*
import scala.concurrent.*

class MonthlyReturnSubmissionWorkItemJobSpec extends SpecBase with LogCapturing {
  private val workerId             = 1
  private val completionLogMessage = "completed work item"

  "MonthlyReturnSubmissionWorkItemJob" - {
    "must complete successfully processed work items as Succeeded" in new TestSetup {
      val completed = Promise[Boolean]()
      when(repository.complete(any(), any())).thenReturn(completed.future)

      withCaptureOfLoggingFrom(Logger(job.getClass)) { events =>
        val result = job.process(workItem)
        verify(repository, mockitoTimeout(1000)).complete(workItem.id, ProcessingStatus.Succeeded)
        events.exists(_.getMessage.contains(completionLogMessage)) mustBe false
        completed.success(true)

        await(result) mustBe true
        events.map(event => event.getLevel -> event.getMessage) mustBe List(
          INFO -> s"[MonthlyReturnSubmissionWorkItemJob][processWorkItem] Worker $workerId processing $context",
          INFO -> s"[MonthlyReturnSubmissionWorkItemJob][processWorkItem] Worker $workerId completed $context"
        )
      }
      verify(repository, never()).markAs(any(), any(), any())
    }

    Seq(BAD_REQUEST, TOO_MANY_REQUESTS, INTERNAL_SERVER_ERROR, SERVICE_UNAVAILABLE).foreach { status =>
      s"must leave an NPS $status response Failed for delayed retry" in new TestSetup {
        when(processing.process(any())).thenReturn(Future.failed(UpstreamErrorResponse("NPS error", status)))

        withCaptureOfLoggingFrom(Logger(job.getClass)) { events =>
          await(job.process(workItem)) mustBe true
          val error = events.find(_.getLevel == ERROR).value
          error.getMessage must include(s"Worker $workerId processing or completion failed for $context")
          error.getMessage must include("marking as [Failed] for delayed retry")
          events.exists(_.getMessage.contains(completionLogMessage)) mustBe false
        }
        verify(repository).markAs(eqTo(workItem.id), eqTo(ProcessingStatus.Failed), any[Option[Instant]])
        verify(repository, never()).complete(any(), any())
      }
    }

    "must mark Failed when processing throws before returning a Future" in new TestSetup {
      when(processing.process(any())).thenThrow(conversionFailure)

      await(job.process(workItem)) mustBe true
      verify(repository).markAs(eqTo(workItem.id), eqTo(ProcessingStatus.Failed), any[Option[Instant]])
    }

    "must retry completion failures without requiring another successful transfer" in new TestSetup {
      when(repository.complete(any(), any())).thenReturn(Future.failed(updateFailure))

      withCaptureOfLoggingFrom(Logger(job.getClass)) { events =>
        await(job.process(workItem)) mustBe true
        events.find(_.getLevel == ERROR).value.getMessage must include(s"processing or completion failed for $context")
        events.exists(_.getMessage.contains(completionLogMessage)) mustBe false
      }
      verify(repository).markAs(eqTo(workItem.id), eqTo(ProcessingStatus.Failed), any[Option[Instant]])
    }

    "must warn without logging completion when the Succeeded update returns false" in new TestSetup {
      when(repository.complete(any(), any())).thenReturn(Future.successful(false))

      withCaptureOfLoggingFrom(Logger(job.getClass)) { events =>
        await(job.process(workItem)) mustBe true
        events.find(_.getLevel == WARN).value.getMessage must include(
          s"Worker $workerId could not mark $context as [Succeeded]"
        )
        events.exists(_.getMessage.contains(completionLogMessage)) mustBe false
      }
      verify(repository, never()).markAs(any(), any(), any())
    }

    "must warn when the Failed update returns false" in new TestSetup {
      when(processing.process(any())).thenReturn(Future.failed(conversionFailure))
      when(repository.markAs(any(), any(), any())).thenReturn(Future.successful(false))

      withCaptureOfLoggingFrom(Logger(job.getClass)) { events =>
        await(job.process(workItem)) mustBe true
        events.find(_.getLevel == WARN).value.getMessage must include(
          s"Worker $workerId could not mark $context as [Failed]"
        )
        events.exists(_.getMessage.contains(completionLogMessage)) mustBe false
      }
    }

    "must retain work-item context and propagate a failed Failed update" in new TestSetup {
      when(processing.process(any())).thenReturn(Future.failed(conversionFailure))
      when(repository.markAs(any(), any(), any())).thenReturn(Future.failed(updateFailure))

      withCaptureOfLoggingFrom(Logger(job.getClass)) { events =>
        await(job.process(workItem).failed) mustBe updateFailure
        events.filter(_.getLevel == ERROR).map(_.getMessage).last must include(
          s"Worker $workerId could not mark $context as [Failed]"
        )
        events.exists(_.getMessage.contains(completionLogMessage)) mustBe false
      }
    }
  }

  private trait TestSetup {
    val conversionFailure = new IllegalStateException("conversion failed")
    val updateFailure     = new RuntimeException("mongo unavailable")
    val workItem          = WorkItem(
      id = new ObjectId(),
      receivedAt = testCreatedOn,
      updatedAt = testCreatedOn,
      availableAt = testCreatedOn,
      status = ProcessingStatus.InProgress,
      failureCount = 0,
      item = MonthlyReturnSubmissionWorkItem(testZReference, testTaxYear, testMonth, testUploadReference)
    )
    val context           =
      s"work item [${workItem.id}] for zReference [$testZReference], taxYear [$testTaxYear], month [$testMonth], submission [$testUploadReference]"
    val repository        = mock[MonthlyReturnSubmissionWorkItemRepository]
    val processing        = mock[MonthlyReturnSubmissionProcessingService]
    when(processing.process(any())).thenReturn(Future.unit)
    when(repository.complete(any(), any())).thenReturn(Future.successful(true))
    when(repository.markAs(any(), any(), any())).thenReturn(Future.successful(true))

    val job = new TestableJob(repository, processing)
  }

  private class TestableJob(
    repository: MonthlyReturnSubmissionWorkItemRepository,
    processing: MonthlyReturnSubmissionProcessingService
  ) extends MonthlyReturnSubmissionWorkItemJob(
        inject[ActorSystem],
        Clock.fixed(testCreatedOn, ZoneOffset.UTC),
        mock[ApplicationLifecycle],
        inject[AppConfig],
        repository,
        processing
      ) {
    def process(workItem: WorkItem[MonthlyReturnSubmissionWorkItem]): Future[Boolean] =
      processWorkItem(workerId, workItem)
  }
}
