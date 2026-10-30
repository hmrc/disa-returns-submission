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

import org.apache.pekko.actor.ActorSystem
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.disareturnssubmission.config.AppConfig
import uk.gov.hmrc.disareturnssubmission.models.monthly.MonthlyReturnSubmissionWorkItem
import uk.gov.hmrc.disareturnssubmission.repositories.MonthlyReturnSubmissionWorkItemRepository
import uk.gov.hmrc.disareturnssubmission.services.MonthlyReturnSubmissionProcessingService
import uk.gov.hmrc.mongo.workitem.*

import java.time.Clock
import javax.inject.*
import scala.concurrent.Future
import scala.util.control.NonFatal

@Singleton
class MonthlyReturnSubmissionWorkItemJob @Inject() (
  actorSystem: ActorSystem,
  clock: Clock,
  lifecycle: ApplicationLifecycle,
  appConfig: AppConfig,
  repository: MonthlyReturnSubmissionWorkItemRepository,
  processingService: MonthlyReturnSubmissionProcessingService
) extends BaseWorkItemJob[MonthlyReturnSubmissionWorkItem](
      actorSystem = actorSystem,
      clock = clock,
      lifecycle = lifecycle,
      workItemRepository = repository,
      dispatcherName = "contexts.monthly-return-submission-work-item",
      pollInterval = appConfig.monthlyReturnSubmissionJobPollInterval,
      failedRetryAfter = appConfig.monthlyReturnSubmissionJobFailedRetryAfter,
      workerCount = appConfig.monthlyReturnSubmissionJobWorkerCount
    ) {

  override protected val jobName: String = "MonthlyReturnSubmissionWorkItemJob"

  override protected def processWorkItem(
    workerId: Int,
    workItem: WorkItem[MonthlyReturnSubmissionWorkItem]
  ): Future[Boolean] = {
    val context =
      s"work item [${workItem.id}] for zReference [${workItem.item.zReference}], taxYear [${workItem.item.taxYear}], month [${workItem.item.month}], submission [${workItem.item.reference}]"
    logger.info(s"[$jobName][processWorkItem] Worker $workerId processing $context")
    Future.unit
      .flatMap(_ => processingService.process(workItem.item))
      .flatMap { _ =>
        repository.complete(workItem.id, ProcessingStatus.Succeeded).map { completed =>
          if (completed) {
            logger.info(s"[$jobName][processWorkItem] Worker $workerId completed $context")
          } else {
            logger.warn(s"[$jobName][processWorkItem] Worker $workerId could not mark $context as [Succeeded]")
          }
        }
      }
      .recoverWith { case NonFatal(exception) =>
        logger.error(
          s"[$jobName][processWorkItem] Worker $workerId processing or completion failed for $context; marking as [Failed] for delayed retry",
          exception
        )
        repository
          .markAs(workItem.id, ProcessingStatus.Failed)
          .map { marked =>
            if (!marked) {
              logger.warn(s"[$jobName][processWorkItem] Worker $workerId could not mark $context as [Failed]")
            }
          }
          .recoverWith { case NonFatal(updateException) =>
            logger.error(
              s"[$jobName][processWorkItem] Worker $workerId could not mark $context as [Failed]",
              updateException
            )
            Future.failed(updateException)
          }
      }
      .map(_ => true)
  }
}
