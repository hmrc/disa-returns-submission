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

package uk.gov.hmrc.disareturnssubmission.repository

import base.SpecBase
import play.api.test.Helpers.*
import uk.gov.hmrc.disareturnssubmission.config.AppConfig
import uk.gov.hmrc.disareturnssubmission.models.monthly.MonthlyReturnSubmissionWorkItem
import uk.gov.hmrc.disareturnssubmission.repositories.MonthlyReturnSubmissionWorkItemRepository
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.mongo.workitem.*

import java.time.*
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Future

class MonthlyReturnSubmissionWorkItemRepositorySpec
    extends SpecBase
    with DefaultPlayMongoRepositorySupport[WorkItem[MonthlyReturnSubmissionWorkItem]] {
  override protected def databaseName: String                                  = "disa-returns-submission-work-item-repository-test"
  override protected def checkTtlIndex: Boolean                                = false
  private val instant                                                          = new AtomicReference(testCreatedOn)
  private val clock                                                            = new Clock {
    override def getZone: ZoneId               = ZoneOffset.UTC
    override def withZone(zone: ZoneId): Clock = this
    override def instant(): Instant            = MonthlyReturnSubmissionWorkItemRepositorySpec.this.instant.get()
  }
  private val config                                                           = inject[AppConfig]
  override protected val repository: MonthlyReturnSubmissionWorkItemRepository =
    new MonthlyReturnSubmissionWorkItemRepository(clock, config, mongoComponent)
  private val item                                                             = MonthlyReturnSubmissionWorkItem(testZReference, testTaxYear, testMonth, testUploadReference)
  private val firstPullTime                                                    = testCreatedOn.plusSeconds(1)

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    instant.set(testCreatedOn)
  }

  override protected def afterAll(): Unit =
    try dropDatabase()
    finally super.afterAll()

  "submission work item repository" - {
    "must deduplicate repeated and concurrent enqueue requests" in {
      await(Future.sequence(Seq.fill(10)(repository.enqueue(item))))
      await(repository.enqueue(item))
      await(repository.collection.countDocuments().head()) mustBe 1L
    }

    "must keep different submissions separate" in {
      await(repository.enqueue(item))
      await(repository.enqueue(item.copy(reference = "other")))
      await(repository.collection.countDocuments().head()) mustBe 2L
    }

    "must atomically claim each item only once" in {
      await(repository.enqueue(item))
      instant.set(firstPullTime)

      await(Future.sequence(Seq.fill(5)(pull()))).flatten.size mustBe 1
    }

    "must not pull a failed item again before failedRetryAfter" in {
      await(repository.enqueue(item))
      instant.set(firstPullTime)
      val workItem = await(pull()).value
      await(repository.markAs(workItem.id, ProcessingStatus.Failed)) mustBe true
      await(pull()) mustBe None

      instant.set(instant.get().plus(config.monthlyReturnSubmissionJobFailedRetryAfter).plusMillis(1))
      val retried = await(pull()).value
      retried.id mustBe workItem.id
      retried.failureCount mustBe 1
    }

    "must recover stuck in-progress items after inProgressRetryAfter" in {
      await(repository.enqueue(item))
      instant.set(firstPullTime)
      val workItem = await(pull()).value
      await(pull()) mustBe None

      instant.set(instant.get().plus(config.monthlyReturnSubmissionJobInProgressRetryAfter).plusMillis(1))
      await(pull()).value.id mustBe workItem.id
    }

    "must retain succeeded items without pulling them again or re-enqueueing them" in {
      await(repository.enqueue(item))
      instant.set(firstPullTime)
      val workItem = await(pull()).value
      await(repository.complete(workItem.id, ProcessingStatus.Succeeded)) mustBe true
      await(repository.enqueue(item))

      instant.set(instant.get().plusSeconds(3600))
      await(pull()) mustBe None
      await(repository.findById(workItem.id)).value.status mustBe ProcessingStatus.Succeeded
      await(repository.collection.countDocuments().head()) mustBe 1L
    }
  }

  private def pull(): Future[Option[WorkItem[MonthlyReturnSubmissionWorkItem]]] =
    repository.pullOutstanding(instant.get().minus(config.monthlyReturnSubmissionJobFailedRetryAfter), instant.get())
}
