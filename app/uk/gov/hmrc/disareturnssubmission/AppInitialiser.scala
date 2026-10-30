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

package uk.gov.hmrc.disareturnssubmission

import org.apache.pekko.Done
import play.api.Logging
import uk.gov.hmrc.disareturnssubmission.config.*
import uk.gov.hmrc.disareturnssubmission.jobs.MonthlyReturnSubmissionWorkItemJob

import javax.inject.*
import scala.concurrent.duration.DurationInt
import scala.concurrent.*
import scala.util.control.NonFatal
import scala.util.Try

@Singleton
class AppInitialiser @Inject() (
  internalAuthTokenInitialiser: InternalAuthTokenInitialiser,
  appConfig: AppConfig,
  monthlyReturnSubmissionWorkItemJob: MonthlyReturnSubmissionWorkItemJob
) extends Logging {

  val initialised: Future[Done] =
    internalAuthTokenInitialiser.initialised

  try {
    Await.result(initialised, 31.seconds)
    logger.info("[AppInitialiser] Internal auth initialiser completed")

    if (appConfig.monthlyReturnSubmissionJobEnabled) {
      Try(monthlyReturnSubmissionWorkItemJob.start()).failed.foreach { exception =>
        logger.error("[AppInitialiser] Monthly return submission work item job failed to start", exception)
      }
    }
  } catch {
    case NonFatal(exception) =>
      logger.error("[AppInitialiser] Internal auth initialiser failed", exception)
      throw exception
  }
}
