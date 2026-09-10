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

  private val otherZReference = "Z5678"
  private val overridesPath   = s"$testServicePath/test-only/overrides"
  private val deletePath      = s"$overridesPath/delete"
  private val overridePath    = s"$overridesPath/$testZReference"
  private val otherPath       = s"$overridesPath/$otherZReference"
  private val statusPath      = s"$testServicePath/reporting-window/status/$testZReference"

  "test override journey" should {

    "bulk replace and expose complete aggregates through single GET" in {
      val result = putJson(
        overridesPath,
        Json.obj(
          "zReferences"     -> Seq(testZReference.toLowerCase, testZReference, otherZReference.toLowerCase),
          "clock"           -> Json.obj("date" -> "2026-06-20"),
          "reportingWindow" -> Json.obj(
            "startDate" -> "2026-06-19T23:59:00Z",
            "endDate"   -> "2026-06-20T00:01:00Z"
          )
        )
      )

      result.status                                              shouldBe NO_CONTENT
      Seq(overridePath, otherPath).foreach { path =>
        val aggregate = get(path).json
        (aggregate \ "clock" \ "date").as[String]                shouldBe "2026-06-20"
        (aggregate \ "reportingWindow" \ "startDate").as[String] shouldBe "2026-06-19T23:59:00Z"
      }
      (get(statusPath).json \ "reportingWindowOpen").as[Boolean] shouldBe true
    }

    "clear omitted fields for every reference during full replacement" in {
      putJson(
        overridesPath,
        Json.obj(
          "zReferences" -> Seq(testZReference, otherZReference),
          "clock"       -> Json.obj("date" -> "2026-06-18")
        )
      ).status shouldBe NO_CONTENT

      Seq(overridePath, otherPath).foreach { path =>
        val aggregate = get(path).json
        (aggregate \ "clock" \ "date").as[String] shouldBe "2026-06-18"
        (aggregate \ "reportingWindow").get       shouldBe JsNull
      }
    }

    "bulk delete complete aggregates" in {
      putJson(
        overridesPath,
        Json.obj("zReferences" -> Seq(testZReference, otherZReference), "clock" -> Json.obj("date" -> "2026-06-20"))
      ).status shouldBe NO_CONTENT

      postJson(
        deletePath,
        Json.obj("zReferences" -> Seq(testZReference.toLowerCase, otherZReference))
      ).status shouldBe NO_CONTENT

      Seq(overridePath, otherPath).foreach { path =>
        val aggregate = get(path).json
        (aggregate \ "clock").get           shouldBe JsNull
        (aggregate \ "reportingWindow").get shouldBe JsNull
      }
    }

    "reject a complete invalid request before replacing any aggregate" in {
      val result = putJson(
        overridesPath,
        Json.obj(
          "zReferences" -> Seq(testZReference, "invalid"),
          "clock"       -> Json.obj("date" -> "2026-06-20")
        )
      )

      result.status                          shouldBe BAD_REQUEST
      (result.json \ "message").as[String]   shouldBe "zReferences must be a non-empty array of valid Z-references"
      (get(overridePath).json \ "clock").get shouldBe JsNull
    }

    "retain single-reference GET" in {
      get(overridePath).status shouldBe OK
    }
  }
}
