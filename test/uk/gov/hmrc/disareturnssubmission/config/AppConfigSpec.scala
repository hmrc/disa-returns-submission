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

package uk.gov.hmrc.disareturnssubmission.config

import base.SpecBase
import com.typesafe.config.ConfigValueFactory
import play.api.Configuration
import play.api.libs.Files.TemporaryFileReaperConfiguration
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import java.time.Duration
import scala.concurrent.duration.DurationInt

class AppConfigSpec extends SpecBase {
  "temporary file reaper configuration" - {
    "must match the backend cleanup settings" in {
      val config = inject[TemporaryFileReaperConfiguration]
      config.enabled mustBe true
      config.initialDelay mustBe 0.minutes
      config.interval mustBe 5.minutes
      config.olderThan mustBe 10.minutes
    }
  }

  "NPS monthly return configuration" - {
    "must read the submission destination and bearer token" in {
      val config = inject[AppConfig]
      config.npsMonthlyReturnSubmissionService mustBe "http://localhost:1204"
      config.npsMonthlyReturnBearerToken mustBe "nps-stub-token"
    }
  }

  "submission work item configuration" - {
    "must read the configured polling and retry settings" in {
      val config = inject[AppConfig]
      config.monthlyReturnSubmissionEnqueueAttempts mustBe 3
      config.monthlyReturnSubmissionJobFailedRetryAfter mustBe Duration.ofMinutes(10)
      config.monthlyReturnSubmissionJobInProgressRetryAfter mustBe Duration.ofMinutes(15)
      config.monthlyReturnSubmissionJobPollInterval mustBe 10.seconds
      config.monthlyReturnSubmissionJobWorkerCount mustBe 2
      configured("enabled", true).monthlyReturnSubmissionJobEnabled mustBe true
      configured("enabled", false).monthlyReturnSubmissionJobEnabled mustBe false
      configured("enqueueAttempts", 2).monthlyReturnSubmissionEnqueueAttempts mustBe 2
    }

  }

  private def configured(key: String, value: Any): AppConfig =
    new AppConfig(
      Configuration(
        app.configuration.underlying.withValue(
          s"monthly-return-submission-work-item-job.$key",
          ConfigValueFactory.fromAnyRef(value)
        )
      ),
      inject[ServicesConfig]
    )
}
