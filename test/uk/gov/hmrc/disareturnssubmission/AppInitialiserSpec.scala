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

import base.SpecBase
import org.apache.pekko.Done
import org.mockito.Mockito.*
import play.api.Logger
import play.api.test.Helpers.*
import uk.gov.hmrc.disareturnssubmission.config.*
import uk.gov.hmrc.disareturnssubmission.jobs.*
import uk.gov.hmrc.play.bootstrap.tools.LogCapturing

import scala.concurrent.Future

class AppInitialiserSpec extends SpecBase with LogCapturing {

  "AppInitialiser" - {
    "must complete construction when internal-auth initialisation succeeds" in {
      val initialiser = internalAuthTokenInitialiser(Future.successful(Done))

      withCaptureOfLoggingFrom(Logger(classOf[AppInitialiser])) { events =>
        val appInitialiser = new AppInitialiser(
          initialiser,
          mock[AppConfig],
          mock[MonthlyReturnSubmissionWorkItemJob],
          mock[MonthlyReturnDeclarationWorkItemJob]
        )

        await(appInitialiser.initialised) mustBe Done
        events.map(_.getMessage) must contain("[AppInitialiser] Internal auth initialiser completed")
      }
    }

    "must fail construction when internal-auth initialisation fails" in {
      val exception   = new RuntimeException("Internal-auth initialisation failed")
      val initialiser = internalAuthTokenInitialiser(Future.failed(exception))

      withCaptureOfLoggingFrom(Logger(classOf[AppInitialiser])) { events =>
        val thrown = intercept[RuntimeException] {
          new AppInitialiser(
            initialiser,
            mock[AppConfig],
            mock[MonthlyReturnSubmissionWorkItemJob],
            mock[MonthlyReturnDeclarationWorkItemJob]
          )
        }

        thrown mustBe exception
        events.last.getMessage mustBe "[AppInitialiser] Internal auth initialiser failed"
      }
    }

    "must start each job independently only when enabled" in {
      for {
        submissionEnabled  <- Seq(false, true)
        declarationEnabled <- Seq(false, true)
      } {
        val config         = mock[AppConfig]
        val submissionJob  = mock[MonthlyReturnSubmissionWorkItemJob]
        val declarationJob = mock[MonthlyReturnDeclarationWorkItemJob]
        when(config.monthlyReturnSubmissionJobEnabled).thenReturn(submissionEnabled)
        when(config.monthlyReturnDeclarationJobEnabled).thenReturn(declarationEnabled)

        new AppInitialiser(internalAuthTokenInitialiser(Future.successful(Done)), config, submissionJob, declarationJob)

        if (submissionEnabled) {
          verify(submissionJob).start()
        } else {
          verifyNoInteractions(submissionJob)
        }
        if (declarationEnabled) {
          verify(declarationJob).start()
        } else {
          verifyNoInteractions(declarationJob)
        }
      }
    }

    "must not start the job if internal-auth initialization fails" in {
      val config         = mock[AppConfig]
      val job            = mock[MonthlyReturnSubmissionWorkItemJob]
      val declarationJob = mock[MonthlyReturnDeclarationWorkItemJob]
      when(config.monthlyReturnSubmissionJobEnabled).thenReturn(true)
      when(config.monthlyReturnDeclarationJobEnabled).thenReturn(true)

      intercept[RuntimeException] {
        new AppInitialiser(
          internalAuthTokenInitialiser(Future.failed(new RuntimeException("auth unavailable"))),
          config,
          job,
          declarationJob
        )
      }
      verifyNoInteractions(job, declarationJob)
    }
  }

  private def internalAuthTokenInitialiser(result: Future[Done]): InternalAuthTokenInitialiser =
    new InternalAuthTokenInitialiser {
      override protected def initialise(): Future[Done] = result
    }
}
