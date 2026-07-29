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

import play.api.http.HeaderNames.{AUTHORIZATION, CONTENT_TYPE}
import play.api.libs.json.JsValue
import play.api.libs.ws.{WSClient, WSResponse, writeableOf_JsValue, writeableOf_String}
import play.api.test.Helpers.await
import play.api.test.{DefaultAwaitTimeout, Helpers}

trait RequestUtils extends DefaultAwaitTimeout {

  protected def ws: WSClient

  protected def serviceUrl(path: String): String

  protected val validInternalAuthToken: String     = "valid-internal-auth-token-disa-returns-backend"
  protected val invalidInternalAuthToken: String   = "invalid-internal-auth-token"
  protected val forbiddenInternalAuthToken: String = "forbidden-internal-auth-token"

  protected def get(path: String): WSResponse =
    getWithAuthorization(path, validInternalAuthToken)

  protected def getWithAuthorization(path: String, authorization: String): WSResponse =
    await(
      ws.url(serviceUrl(path))
        .withHttpHeaders(AUTHORIZATION -> authorization)
        .get()
    )

  protected def getWithoutAuthorization(path: String): WSResponse =
    await(
      ws.url(serviceUrl(path))
        .get()
    )

  protected def delete(path: String): WSResponse =
    await(
      ws.url(serviceUrl(path))
        .delete()
    )

  protected def postJson(path: String, body: String, withContent: String): WSResponse =
    postJsonWithAuthorization(path, body, withContent, validInternalAuthToken)

  protected def postJsonWithAuthorization(path: String, body: String, withContent: String, authorization: String): WSResponse =
    await(
      ws.url(serviceUrl(path)).withHttpHeaders("Content-Type" -> withContent)
        .withHttpHeaders(AUTHORIZATION -> authorization)
        .post(body)
    )

  protected def postJsonWithoutAuthorization(path: String, body: String, withContent: String): WSResponse =
    await(
      ws.url(serviceUrl(path)).withHttpHeaders("Content-Type" -> withContent)
        .post(body)
    )

  protected def postJson(path: String, body: JsValue): WSResponse =
    postJsonWithAuthorization(path, body, validInternalAuthToken)

  protected def postJsonWithAuthorization(path: String, body: JsValue, authorization: String): WSResponse =
    await(
      ws.url(serviceUrl(path))
        .withHttpHeaders(AUTHORIZATION -> authorization)
        .post(body)
    )

  protected def postJsonWithoutAuthorization(path: String, body: JsValue): WSResponse =
    await(
      ws.url(serviceUrl(path))
        .post(body)
    )

  protected def postJson(path: String): WSResponse =
    await(
      ws.url(serviceUrl(path))
        .post("")
    )

  protected def putString(path: String, body: String = ""): WSResponse =
    await(
      ws.url(serviceUrl(path))
        .put(body)
    )

  protected def putString(path: String, body: String, contentType: String): WSResponse =
    putStringWithAuthorization(path, body, contentType, validInternalAuthToken)

  protected def putStringWithAuthorization(
    path: String,
    body: String,
    contentType: String,
    authorization: String
  ): WSResponse =
    await(
      ws.url(serviceUrl(path))
        .withHttpHeaders(
          CONTENT_TYPE  -> contentType,
          AUTHORIZATION -> authorization
        )
        .put(body)
    )

  protected def putStringWithoutAuthorization(path: String, body: String, contentType: String): WSResponse =
    await(
      ws.url(serviceUrl(path))
        .withHttpHeaders(CONTENT_TYPE -> contentType)
        .put(body)
    )

  protected def putJson(path: String, body: JsValue): WSResponse =
    await(
      ws.url(serviceUrl(path))
        .put(body)
    )

  protected def postString(path: String, body: String, contentType: String): WSResponse =
    await(
      ws.url(serviceUrl(path))
        .withHttpHeaders(CONTENT_TYPE -> contentType)
        .post(body)
    )
}
