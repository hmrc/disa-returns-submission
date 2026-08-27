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

package uk.gov.hmrc.disareturnssubmission.controllers

import play.api.Application
import play.api.http.Status.{BAD_REQUEST, NO_CONTENT, OK}
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import uk.gov.hmrc.disareturnssubmission.BaseIntegrationSpec
import uk.gov.hmrc.play.audit.http.connector.DatastreamMetrics

class ReportingWindowOverrideISpec extends BaseIntegrationSpec {

  override lazy val app: Application = new GuiceApplicationBuilder()
    .configure(config + ("application.router" -> "testOnlyDoNotUseInAppConf.Routes"))
    .overrides(bind[DatastreamMetrics].toInstance(DatastreamMetrics.disabled))
    .build()

  private val clockPath    = s"$testServicePath/test-only/clock"
  private val overridePath = s"$testServicePath/test-only/reporting-window-override/$testZReference"
  private val statusPath   = s"$testServicePath/reporting-window/status/$testZReference"

  "reporting window override journey" should {

    "store and apply an override only to the normalized Z-reference" in {
      putString(s"$clockPath/2026-06-20").status shouldBe OK

      val overrideResult = putJson(
        overridePath.toLowerCase,
        Json.obj(
          "startDate" -> "2026-06-19T23:59:00Z",
          "endDate"   -> "2026-06-20T00:01:00Z"
        )
      )

      overrideResult.status                                     shouldBe NO_CONTENT
      (get(statusPath).json \ "reportingWindowOpen").as[Boolean] shouldBe true
      (get(s"$testServicePath/reporting-window/status/Z5678").json \ "reportingWindowOpen").as[Boolean] shouldBe false
    }

    "reject invalid override requests" in {
      val result = putJson(
        overridePath,
        Json.obj(
          "startDate" -> "2026-06-20T00:01:00Z",
          "endDate"   -> "2026-06-19T23:59:00Z"
        )
      )

      result.status shouldBe BAD_REQUEST
    }
  }
}
