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
import uk.gov.hmrc.disareturnssubmission.models.monthly.MonthlyReturnDeclarationWorkItem
import uk.gov.hmrc.disareturnssubmission.repositories.MonthlyReturnDeclarationWorkItemRepository
import uk.gov.hmrc.disareturnssubmission.services.*
import uk.gov.hmrc.mongo.workitem.*

import java.time.Clock
import javax.inject.*
import scala.concurrent.Future
import scala.util.control.NonFatal

@Singleton
class MonthlyReturnDeclarationWorkItemJob @Inject() (
  actorSystem: ActorSystem,
  clock: Clock,
  lifecycle: ApplicationLifecycle,
  appConfig: AppConfig,
  repository: MonthlyReturnDeclarationWorkItemRepository,
  processingService: MonthlyReturnDeclarationProcessingService
) extends BaseWorkItemJob[MonthlyReturnDeclarationWorkItem](
      actorSystem = actorSystem,
      clock = clock,
      lifecycle = lifecycle,
      workItemRepository = repository,
      dispatcherName = "contexts.monthly-return-declaration-work-item",
      pollInterval = appConfig.monthlyReturnDeclarationJobPollInterval,
      failedRetryAfter = appConfig.monthlyReturnDeclarationJobFailedRetryAfter,
      workerCount = appConfig.monthlyReturnDeclarationJobWorkerCount
    ) {

  override protected val jobName: String = "MonthlyReturnDeclarationWorkItemJob"

  override protected def processWorkItem(
    workerId: Int,
    workItem: WorkItem[MonthlyReturnDeclarationWorkItem]
  ): Future[Boolean] = {
    val loggingContext =
      s"work item [${workItem.id}] for zReference [${workItem.item.zReference}], taxYear [${workItem.item.taxYear}], month [${workItem.item.month}]"
    logger.info(s"[MonthlyReturnDeclarationWorkItemJob][processWorkItem] Worker $workerId processing $loggingContext")
    Future.unit
      .flatMap(_ => processingService.process(workItem.item))
      .flatMap {
        case MonthlyReturnDeclarationProcessingResult.Declared           =>
          repository.complete(workItem.id, ProcessingStatus.Succeeded).map { completed =>
            if (completed) {
              logger.info(
                s"[MonthlyReturnDeclarationWorkItemJob][processWorkItem] Worker $workerId completed $loggingContext"
              )
            } else {
              logger.warn(
                s"[MonthlyReturnDeclarationWorkItemJob][processWorkItem] Worker $workerId could not mark $loggingContext as [Succeeded]"
              )
            }
          }
        case MonthlyReturnDeclarationProcessingResult.PendingSubmissions =>
          val attemptAgainAfter =
            clock.instant().plus(appConfig.monthlyReturnDeclarationJobPendingSubmissionsRetryAfter)
          repository.markAs(workItem.id, ProcessingStatus.ToDo, Some(attemptAgainAfter)).map { marked =>
            if (marked) {
              logger.info(
                s"[MonthlyReturnDeclarationWorkItemJob][processWorkItem] Worker $workerId waiting for submissions for $loggingContext - queued as [ToDo] until [$attemptAgainAfter]"
              )
            } else {
              logger.warn(
                s"[MonthlyReturnDeclarationWorkItemJob][processWorkItem] Worker $workerId could not mark $loggingContext as [ToDo]"
              )
            }
          }
      }
      .recoverWith { case NonFatal(exception) =>
        logger.error(
          s"[MonthlyReturnDeclarationWorkItemJob][processWorkItem] Worker $workerId processing or completion failed for $loggingContext - marking as [Failed] for delayed retry",
          exception
        )
        repository
          .markAs(workItem.id, ProcessingStatus.Failed)
          .map { marked =>
            if (!marked) {
              logger.warn(
                s"[MonthlyReturnDeclarationWorkItemJob][processWorkItem] Worker $workerId could not mark $loggingContext as [Failed]"
              )
            }
          }
          .recoverWith { case NonFatal(updateException) =>
            logger.error(
              s"[MonthlyReturnDeclarationWorkItemJob][processWorkItem] Worker $workerId could not mark $loggingContext as [Failed]",
              updateException
            )
            Future.failed(updateException)
          }
      }
      .map(_ => true)
  }
}
