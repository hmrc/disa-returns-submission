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
import play.api.http.HeaderNames.*
import play.api.http.MimeTypes.JSON
import play.api.http.Status.*
import uk.gov.hmrc.disareturnssubmission.utils.Constants.NDJSON_MIME_TYPE

import java.nio.charset.StandardCharsets.UTF_8

trait ObjectStoreWireMockStubs {

  private val objectStorePath = "/object-store/object/disa-returns-submission/[^/]+"

  protected def stubObjectStorePut(): Unit =
    stubFor(
      put(urlPathMatching(objectStorePath))
        .willReturn(
          aResponse()
            .withStatus(OK)
            .withHeader(CONTENT_TYPE, JSON)
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

  protected def stubObjectStoreGet(body: String): Unit =
    stubFor(
      get(urlPathMatching(objectStorePath))
        .willReturn(
          aResponse()
            .withStatus(OK)
            .withHeader(CONTENT_TYPE, NDJSON_MIME_TYPE)
            .withHeader(CONTENT_LENGTH, body.getBytes(UTF_8).length.toString)
            .withHeader(CONTENT_MD5, "CY9rzUYh03PK3k6DJie09g==")
            .withHeader(LAST_MODIFIED, "Sun, 17 May 2026 12:00:00 GMT")
            .withBody(body)
        )
    )

}
