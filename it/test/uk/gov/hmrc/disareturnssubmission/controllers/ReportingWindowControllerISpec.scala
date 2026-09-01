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

import play.api.http.Status.{FORBIDDEN, OK, UNAUTHORIZED}
import uk.gov.hmrc.disareturnssubmission.BaseIntegrationSpec

class ReportingWindowControllerISpec extends BaseIntegrationSpec {

  private val reportingWindowStatusPath = s"$testServicePath/reporting-window/status/$testZReference"

  "GET /reporting-window/status/:zReference" should {

    "return 200 OK with reportingWindowOpen true when today falls within the declaration period" in {
      val result = get(reportingWindowStatusPath)

      result.status                                     shouldBe OK
      (result.json \ "reportingWindowOpen").as[Boolean] shouldBe true
    }

    "return 401 Unauthorized when no internal auth token is provided" in {
      val result = getWithoutAuthorization(reportingWindowStatusPath)

      result.status shouldBe UNAUTHORIZED
    }

    "return 401 Unauthorized when the internal auth token is invalid" in {
      val result = getWithAuthorization(reportingWindowStatusPath, invalidInternalAuthToken)

      result.status shouldBe UNAUTHORIZED
    }

    "return 403 Forbidden when the internal auth token does not have permission" in {
      val result = getWithAuthorization(reportingWindowStatusPath, forbiddenInternalAuthToken)

      result.status shouldBe FORBIDDEN
    }
  }
}
