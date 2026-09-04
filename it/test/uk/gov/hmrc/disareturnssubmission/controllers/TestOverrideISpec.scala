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
import play.api.http.Status.{BAD_REQUEST, OK}
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.{JsNull, Json}
import uk.gov.hmrc.disareturnssubmission.BaseIntegrationSpec
import uk.gov.hmrc.play.audit.http.connector.DatastreamMetrics

import java.time.{Clock, ZoneOffset}

class TestOverrideISpec extends BaseIntegrationSpec {

  override lazy val app: Application = new GuiceApplicationBuilder()
    .configure(config + ("application.router" -> "testOnlyDoNotUseInAppConf.Routes"))
    .overrides(
      bind[Clock].toInstance(Clock.fixed(integrationTestNow, ZoneOffset.UTC)),
      bind[DatastreamMetrics].toInstance(DatastreamMetrics.disabled)
    )
    .build()

  private val overridePath = s"$testServicePath/test-only/overrides/$testZReference"
  private val statusPath   = s"$testServicePath/reporting-window/status/$testZReference"

  "test override journey" should {

    "replace and return the complete aggregate" in {
      val result = putJson(
        overridePath.toLowerCase,
        Json.obj(
          "clock" -> Json.obj("date" -> "2026-06-20"),
          "reportingWindow" -> Json.obj(
            "startDate" -> "2026-06-19T23:59:00Z",
            "endDate"   -> "2026-06-20T00:01:00Z"
          )
        )
      )

      result.status shouldBe OK
      result.json shouldBe Json.obj(
        "zReference"          -> testZReference,
        "clock"               -> Json.obj("date" -> "2026-06-20"),
        "reportingWindow" -> Json.obj(
          "startDate" -> "2026-06-19T23:59:00Z",
          "endDate"   -> "2026-06-20T00:01:00Z"
        )
      )
      get(overridePath).json shouldBe result.json
      (get(statusPath).json \ "reportingWindowOpen").as[Boolean] shouldBe true
    }

    "clear omitted fields during full replacement" in {
      putJson(
        overridePath,
        Json.obj(
          "clock" -> Json.obj("date" -> "2026-06-20"),
          "reportingWindow" -> Json.obj(
            "startDate" -> "2026-06-19T23:59:00Z",
            "endDate"   -> "2026-06-20T00:01:00Z"
          )
        )
      ).status shouldBe OK

      val result = putJson(overridePath, Json.obj("clock" -> Json.obj("date" -> "2026-06-18")))

      result.status shouldBe OK
      (result.json \ "clock" \ "date").as[String] shouldBe "2026-06-18"
      (result.json \ "reportingWindow").get shouldBe JsNull
    }

    "delete all override fields" in {
      putJson(overridePath, Json.obj("clock" -> Json.obj("date" -> "2026-06-20"))).status shouldBe OK

      val result = delete(overridePath)

      result.status shouldBe OK
      (result.json \ "clock").get shouldBe JsNull
      (result.json \ "reportingWindow").get shouldBe JsNull
      get(overridePath).json shouldBe result.json
    }

    "reject invalid aggregate requests" in {
      val result = putJson(
        overridePath,
        Json.obj(
          "reportingWindow" -> Json.obj(
            "startDate" -> "2026-06-20T00:01:00Z",
            "endDate"   -> "2026-06-19T23:59:00Z"
          )
        )
      )

      result.status shouldBe BAD_REQUEST
    }
  }
}
