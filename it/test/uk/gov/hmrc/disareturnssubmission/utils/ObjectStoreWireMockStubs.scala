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

package uk.gov.hmrc.disareturnssubmission.utils

import com.github.tomakehurst.wiremock.client.WireMock.*
import play.api.http.Status.INTERNAL_SERVER_ERROR


trait ObjectStoreWireMockStubs {

  private val objectStorePath = "/object-store/object/disa-returns-submission/[^/]+"

  protected def stubObjectStorePut(): Unit =
    stubFor(
      put(urlPathMatching(objectStorePath))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody(
              """{
                |  "location": "object-store/object/disa-returns-submission/file",
                |  "contentLength": 123,
                |  "contentMD5": "CY9rzUYh03PK3k6DJie09g==",
                |  "lastModified": "2026-05-17T12:00:00Z"
                |} """.stripMargin
            )
        )
    )

  protected def stubObjectStorePutInternalServerError(): Unit =
    stubFor(
      put(urlPathMatching(objectStorePath))
        .willReturn(aResponse().withStatus(INTERNAL_SERVER_ERROR))
    )

}
