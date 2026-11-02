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
import uk.gov.hmrc.disareturnssubmission.models.monthly.MonthlyReturnDeclarationWorkItem
import uk.gov.hmrc.disareturnssubmission.repositories.MonthlyReturnDeclarationWorkItemRepository
import uk.gov.hmrc.disareturnssubmission.services.*
import uk.gov.hmrc.disareturnssubmission.services.MonthlyReturnDeclarationProcessingResult.*
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.mongo.workitem.*
import uk.gov.hmrc.play.bootstrap.tools.LogCapturing

import java.time.*
import scala.concurrent.*

class MonthlyReturnDeclarationWorkItemJobSpec extends SpecBase with LogCapturing {
  private val workerId         = 1
  private val completedMessage = "completed work item"

  "MonthlyReturnDeclarationWorkItemJob" - {
    "must await completion before logging success" in new TestSetup {
      val completed = Promise[Boolean]()
      when(repository.complete(any(), any())).thenReturn(completed.future)

      withCaptureOfLoggingFrom(Logger(job.getClass)) { events =>
        val result = job.process(workItem)
        verify(repository, mockitoTimeout(1000)).complete(workItem.id, ProcessingStatus.Succeeded)
        events.exists(_.getMessage.contains(completedMessage)) mustBe false
        completed.success(true)
        await(result) mustBe true
        events.last.getMessage must include(completedMessage)
      }
      verify(repository, never()).markAs(any(), any(), any())
    }

    "must requeue pending submissions as ToDo using the configured delay" in new TestSetup {
      when(processing.process(any())).thenReturn(Future.successful(PendingSubmissions))

      await(job.process(workItem)) mustBe true
      val availableAt = testCreatedOn.plus(config.monthlyReturnDeclarationJobPendingSubmissionsRetryAfter)
      verify(repository).markAs(workItem.id, ProcessingStatus.ToDo, Some(availableAt))
      verify(repository, never()).complete(any(), any())
    }

    Seq(BAD_REQUEST, TOO_MANY_REQUESTS, INTERNAL_SERVER_ERROR, SERVICE_UNAVAILABLE).foreach { status =>
      s"must mark Failed for delayed retry after an NPS [$status] failure" in new TestSetup {
        when(processing.process(any()))
          .thenReturn(Future.failed(UpstreamErrorResponse("NPS declaration failed", status)))

        await(job.process(workItem)) mustBe true
        verify(repository).markAs(eqTo(workItem.id), eqTo(ProcessingStatus.Failed), any[Option[Instant]])
        verify(repository, never()).complete(any(), any())
      }
    }

    "must handle processing that throws before returning a Future" in new TestSetup {
      when(processing.process(any())).thenThrow(processingFailure)

      await(job.process(workItem)) mustBe true
      verify(repository).markAs(eqTo(workItem.id), eqTo(ProcessingStatus.Failed), any[Option[Instant]])
    }

    "must mark Failed if completing a declaration throws" in new TestSetup {
      when(repository.complete(any(), any())).thenReturn(Future.failed(updateFailure))

      await(job.process(workItem)) mustBe true
      verify(repository).markAs(eqTo(workItem.id), eqTo(ProcessingStatus.Failed), any[Option[Instant]])
    }

    "must mark Failed if requeueing pending submissions throws" in new TestSetup {
      when(processing.process(any())).thenReturn(Future.successful(PendingSubmissions))
      when(repository.markAs(any(), eqTo(ProcessingStatus.ToDo), any())).thenReturn(Future.failed(updateFailure))

      await(job.process(workItem)) mustBe true
      verify(repository).markAs(eqTo(workItem.id), eqTo(ProcessingStatus.Failed), any[Option[Instant]])
    }

    Seq(ProcessingStatus.Succeeded, ProcessingStatus.ToDo, ProcessingStatus.Failed).foreach { status =>
      s"must warn without claiming success when the [$status] update returns false" in new TestSetup {
        status match {
          case ProcessingStatus.ToDo   =>
            when(processing.process(any())).thenReturn(Future.successful(PendingSubmissions))
          case ProcessingStatus.Failed => when(processing.process(any())).thenReturn(Future.failed(processingFailure))
          case _                       => ()
        }
        when(repository.complete(any(), any())).thenReturn(Future.successful(false))
        when(repository.markAs(any(), any(), any())).thenReturn(Future.successful(false))

        withCaptureOfLoggingFrom(Logger(job.getClass)) { events =>
          await(job.process(workItem)) mustBe true
          events.find(_.getLevel == WARN).value.getMessage must include(s"could not mark $loggingContext as [$status]")
          events.exists(_.getMessage.contains(completedMessage)) mustBe false
        }
      }
    }

    "must retain work-item details and propagate a failed Failed update" in new TestSetup {
      when(processing.process(any())).thenReturn(Future.failed(processingFailure))
      when(repository.markAs(any(), any(), any())).thenReturn(Future.failed(updateFailure))

      withCaptureOfLoggingFrom(Logger(job.getClass)) { events =>
        await(job.process(workItem).failed) mustBe updateFailure
        events.filter(_.getLevel == ERROR).last.getMessage must include(s"could not mark $loggingContext as [Failed]")
      }
    }
  }

  private trait TestSetup {
    val config            = inject[AppConfig]
    val repository        = mock[MonthlyReturnDeclarationWorkItemRepository]
    val processing        = mock[MonthlyReturnDeclarationProcessingService]
    val processingFailure = new IllegalStateException("declaration failed")
    val updateFailure     = new RuntimeException("mongo unavailable")
    val workItem          = WorkItem(
      id = new ObjectId(),
      receivedAt = testCreatedOn,
      updatedAt = testCreatedOn,
      availableAt = testCreatedOn,
      status = ProcessingStatus.InProgress,
      failureCount = 0,
      item = MonthlyReturnDeclarationWorkItem(testZReference, testTaxYear, testMonth)
    )
    val loggingContext    =
      s"work item [${workItem.id}] for zReference [$testZReference], taxYear [$testTaxYear], month [$testMonth]"
    when(processing.process(any())).thenReturn(Future.successful(Declared))
    when(repository.complete(any(), any())).thenReturn(Future.successful(true))
    when(repository.markAs(any(), any(), any())).thenReturn(Future.successful(true))
    val job               = new TestableJob(repository, processing)
  }

  private class TestableJob(
    repository: MonthlyReturnDeclarationWorkItemRepository,
    processing: MonthlyReturnDeclarationProcessingService
  ) extends MonthlyReturnDeclarationWorkItemJob(
        inject[ActorSystem],
        Clock.fixed(testCreatedOn, ZoneOffset.UTC),
        mock[ApplicationLifecycle],
        inject[AppConfig],
        repository,
        processing
      ) {
    def process(workItem: WorkItem[MonthlyReturnDeclarationWorkItem]): Future[Boolean] =
      processWorkItem(workerId, workItem)
  }
}
