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
import uk.gov.hmrc.disareturnssubmission.models.monthly.MonthlyReturnDeclarationWorkItem
import uk.gov.hmrc.disareturnssubmission.repositories.MonthlyReturnDeclarationWorkItemRepository
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.mongo.workitem.*

import java.time.*
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Future

class MonthlyReturnDeclarationWorkItemRepositorySpec
    extends SpecBase
    with DefaultPlayMongoRepositorySupport[WorkItem[MonthlyReturnDeclarationWorkItem]] {
  override protected def databaseName: String                                   = "disa-returns-submission-declaration-work-item-repository-test"
  override protected def checkTtlIndex: Boolean                                 = false
  private val instant                                                           = new AtomicReference(testCreatedOn)
  private val clock                                                             = new Clock {
    override def getZone: ZoneId               = ZoneOffset.UTC
    override def withZone(zone: ZoneId): Clock = this
    override def instant(): Instant            = MonthlyReturnDeclarationWorkItemRepositorySpec.this.instant.get()
  }
  private val config                                                            = inject[AppConfig]
  override protected val repository: MonthlyReturnDeclarationWorkItemRepository =
    new MonthlyReturnDeclarationWorkItemRepository(clock, config, mongoComponent)
  private val item                                                              = MonthlyReturnDeclarationWorkItem(testZReference, testTaxYear, testMonth)
  private val firstPullTime                                                     = testCreatedOn.plusMillis(1)

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    instant.set(testCreatedOn)
  }

  override protected def afterAll(): Unit =
    try dropDatabase()
    finally super.afterAll()

  "declaration work item repository" - {
    "must deduplicate repeated and concurrent enqueues" in {
      await(Future.sequence(Seq.fill(10)(repository.enqueue(item))))
      await(repository.enqueue(item))
      await(repository.collection.countDocuments().head()) mustBe 1L
    }

    "must keep different monthly return keys separate" in {
      val otherZReference = "Z5678"
      val otherTaxYear    = "2027-28"
      val items           = Seq(
        item,
        item.copy(zReference = otherZReference),
        item.copy(taxYear = otherTaxYear),
        item.copy(month = testMonth + 1)
      )
      await(Future.sequence(items.map(repository.enqueue)))
      await(repository.collection.countDocuments().head()) mustBe items.size.toLong
    }

    "must atomically claim a work item only once" in {
      await(repository.enqueue(item))
      instant.set(firstPullTime)
      await(Future.sequence(Seq.fill(5)(pull()))).flatten.size mustBe 1
    }

    "must atomically requeue ToDo until availableAt without counting waiting as a failure" in {
      await(repository.enqueue(item))
      instant.set(firstPullTime)
      val workItem    = await(pull()).value
      val availableAt = firstPullTime.plus(config.monthlyReturnDeclarationJobPendingSubmissionsRetryAfter)
      await(repository.markAs(workItem.id, ProcessingStatus.ToDo, Some(availableAt))) mustBe true
      val deferred    = await(repository.findById(workItem.id)).value
      deferred.status mustBe ProcessingStatus.ToDo
      deferred.availableAt mustBe availableAt
      deferred.receivedAt mustBe workItem.receivedAt
      repository.workItemFields.availableAt mustBe "availableAt"
      deferred.failureCount mustBe 0
      await(repository.enqueue(item))
      await(repository.findById(workItem.id)).value.availableAt mustBe availableAt

      instant.set(availableAt.minusMillis(1))
      await(pull()) mustBe None
      instant.set(availableAt)
      await(pull()) mustBe None
      instant.set(availableAt.plusMillis(1))
      await(pull()).value.id mustBe workItem.id
    }

    "must delay failures by failedRetryAfter" in {
      await(repository.enqueue(item))
      instant.set(firstPullTime)
      val workItem    = await(pull()).value
      await(repository.markAs(workItem.id, ProcessingStatus.Failed)) mustBe true
      val availableAt = firstPullTime.plus(config.monthlyReturnDeclarationJobFailedRetryAfter)
      instant.set(availableAt.minusMillis(1))
      await(pull()) mustBe None
      instant.set(availableAt.plusMillis(1))
      val retried     = await(pull()).value
      retried.id mustBe workItem.id
      retried.failureCount mustBe 1
    }

    "must recover an in-progress item after inProgressRetryAfter" in {
      await(repository.enqueue(item))
      instant.set(firstPullTime)
      val workItem = await(pull()).value
      await(pull()) mustBe None
      instant.set(firstPullTime.plus(config.monthlyReturnDeclarationJobInProgressRetryAfter).plusMillis(1))
      await(pull()).value.id mustBe workItem.id
    }

    "must retain succeeded items without re-enqueueing or pulling them again" in {
      await(repository.enqueue(item))
      instant.set(firstPullTime)
      val workItem = await(pull()).value
      await(repository.complete(workItem.id, ProcessingStatus.Succeeded)) mustBe true
      await(repository.enqueue(item))
      instant.set(firstPullTime.plusSeconds(3600))
      await(pull()) mustBe None
      await(repository.findById(workItem.id)).value.status mustBe ProcessingStatus.Succeeded
      await(repository.collection.countDocuments().head()) mustBe 1L
    }
  }

  private def pull(): Future[Option[WorkItem[MonthlyReturnDeclarationWorkItem]]] =
    repository.pullOutstanding(instant.get().minus(config.monthlyReturnDeclarationJobFailedRetryAfter), instant.get())
}
