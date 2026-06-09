package uk.gov.hmrc.disareturnssubmission.utils

import play.api.http.HeaderNames.CONTENT_TYPE
import play.api.libs.json.JsValue
import play.api.libs.ws.{WSClient, WSResponse, writeableOf_JsValue, writeableOf_String}
import play.api.test.Helpers.await
import play.api.test.{DefaultAwaitTimeout, Helpers}

trait RequestUtils extends DefaultAwaitTimeout {

  protected def ws: WSClient

  protected def serviceUrl(path: String): String

  protected def get(path: String): WSResponse =
    await(
      ws.url(serviceUrl(path))
        .get()
    )

  protected def delete(path: String): WSResponse =
    await(
      ws.url(serviceUrl(path))
        .delete()
    )

  protected def postJson(path: String, body: JsValue): WSResponse =
    await(
      ws.url(serviceUrl(path))
        .post(body)
    )

  protected def putString(path: String, body: String = ""): WSResponse =
    await(
      ws.url(serviceUrl(path))
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
