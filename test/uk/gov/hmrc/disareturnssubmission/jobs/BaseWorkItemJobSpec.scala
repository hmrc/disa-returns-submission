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
import org.apache.pekko.actor.ActorSystem
import org.bson.types.ObjectId
import org.mockito.ArgumentMatchers.{eq as eqTo, *}
import org.mockito.Mockito.{timeout as mockitoTimeout, *}
import play.api.inject.ApplicationLifecycle
import play.api.test.Helpers.*
import uk.gov.hmrc.mongo.workitem.*

import java.time.*
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.*
import scala.concurrent.duration.{Duration as _, *}

class BaseWorkItemJobSpec extends SpecBase {
  "BaseWorkItemJob" - {
    "must use the retry cutoff and configured worker count, and start only once" in new TestSetup {
      job.start()
      job.start()

      verify(repository, mockitoTimeout(1000).times(2))
        .pullOutstanding(eqTo(testCreatedOn.minusSeconds(600)), eqTo(testCreatedOn))
      await(lifecycle.stop())
    }

    "must process outstanding items and immediately poll again" in new TestSetup {
      val item =
        WorkItem(new ObjectId(), testCreatedOn, testCreatedOn, testCreatedOn, ProcessingStatus.InProgress, 0, "item")
      when(repository.pullOutstanding(any(), any())).thenReturn(Future.successful(Some(item)), Future.successful(None))

      job.start()

      await(processed.future) mustBe item
      verify(repository, mockitoTimeout(1000).times(3)).pullOutstanding(any(), any())
      await(lifecycle.stop())
    }

    "must stop polling when the application lifecycle stops" in new TestSetup {
      override def jobPollInterval: FiniteDuration = 25.millis

      val calls = new AtomicInteger()
      when(repository.pullOutstanding(any(), any())).thenAnswer { _ =>
        calls.incrementAndGet()
        Future.successful(None)
      }

      job.start()
      verify(repository, mockitoTimeout(1000).atLeast(2)).pullOutstanding(any(), any())
      await(lifecycle.stop())
      val stoppedCount = calls.get()
      Thread.sleep(100)
      calls.get() mustBe stoppedCount
    }
  }

  private trait TestSetup {
    val repository                      = mock[WorkItemRepository[String]]
    val lifecycle                       = new CapturingLifecycle
    val processed                       = Promise[WorkItem[String]]()
    when(repository.pullOutstanding(any(), any())).thenReturn(Future.successful(None))
    def jobPollInterval: FiniteDuration = 1.hour

    lazy val job = new BaseWorkItemJob[String](
      inject[ActorSystem],
      Clock.fixed(testCreatedOn, ZoneOffset.UTC),
      lifecycle,
      repository,
      "contexts.monthly-return-submission-work-item",
      jobPollInterval,
      Duration.ofMinutes(10),
      workerCount = 2
    ) {
      override protected val jobName                                                                     = "TestWorkItemJob"
      override protected def processWorkItem(workerId: Int, workItem: WorkItem[String]): Future[Boolean] = {
        processed.trySuccess(workItem)
        Future.successful(true)
      }
    }
  }

  private class CapturingLifecycle extends ApplicationLifecycle {
    private var hook: () => Future[?]                         = () => Future.unit
    override def addStopHook(stopHook: () => Future[?]): Unit = hook = stopHook
    override def stop(): Future[?]                            = hook()
  }
}
