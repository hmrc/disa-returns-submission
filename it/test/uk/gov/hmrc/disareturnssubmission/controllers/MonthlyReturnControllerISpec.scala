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

import play.api.http.Status.{BAD_REQUEST, CONFLICT, CREATED, FORBIDDEN, NOT_FOUND, OK, SERVICE_UNAVAILABLE, UNAUTHORIZED, UNPROCESSABLE_ENTITY}
import play.api.libs.json.{JsValue, Json}
import uk.gov.hmrc.disareturnssubmission.BaseIntegrationSpec
import uk.gov.hmrc.disareturnssubmission.utils.ObjectStoreWireMockStubs


class MonthlyReturnControllerISpec extends BaseIntegrationSpec with ObjectStoreWireMockStubs {

  private val monthlyPath = s"$testServicePath/monthly/$testZReference/$testTaxYear/$testMonth"
  private val invalidMonthlyPath = s"$testServicePath/monthly/$invalidTestZReference/$invalidTestTaxYear/$invalidTestMonth"
  private val currentMonthPath = s"$testServicePath/monthly/$testZReference/$testTaxYear/6"

  private val declarationPath = monthlyPath ++ "/declarations"
  private val submissionPath  = monthlyPath ++ s"/submissions/$testUploadReference"
  private val currentMonthDeclarationPath = currentMonthPath ++ "/declarations"


  "POST /monthly/:zReference/:taxYear/:month" should {

    "return 201 Created when the monthly return is created successfully" in {
      val result       = postJson(monthlyPath, nilReturnFalseRequest)
      val submissionId = (result.json \ submissionIdFieldName).as[String]

      result.status shouldBe CREATED
      (result.json \ submissionIdFieldName).as[String] shouldBe submissionId
    }

    "return 400 Bad Request when path parameters are invalid" in {
      val result = postJson(invalidMonthlyPath, nilReturnFalseRequest)

      result.status shouldBe BAD_REQUEST
    }

    "return 409 Conflict when the monthly return was already created successfully" in {
      postJson(monthlyPath, nilReturnFalseRequest)

      val result = postJson(monthlyPath, nilReturnFalseRequest)

      result.status shouldBe CONFLICT
    }

    "return 422 UnprocessableEntity when the monthly return is not for the previous monthly period" in {
      val result = postJson(currentMonthPath, nilReturnFalseRequest)

      result.status shouldBe UNPROCESSABLE_ENTITY
    }

    "return 401 Unauthorized when no internal auth token is provided" in {
      val result = postJsonWithoutAuthorization(monthlyPath, nilReturnFalseRequest)

      result.status shouldBe UNAUTHORIZED
    }

    "return 401 Unauthorized when the internal auth token is invalid" in {
      val result = postJsonWithAuthorization(monthlyPath, nilReturnFalseRequest, invalidInternalAuthToken)

      result.status shouldBe UNAUTHORIZED
    }

    "return 403 Forbidden when the internal auth token does not have permission" in {
      val result = postJsonWithAuthorization(monthlyPath, nilReturnFalseRequest, forbiddenInternalAuthToken)

      result.status shouldBe FORBIDDEN
    }
  }

  "GET /monthly/:zReference/:taxYear/:month" should {
    "return 200 OK when the monthly return exists" in {
      postJson(monthlyPath, nilReturnFalseRequest).status shouldBe CREATED
      val result = get(monthlyPath)

      result.status shouldBe OK
    }

    "return 404 NotFound when the monthly return doesn't exists" in {
      val result = get(monthlyPath)

      result.status shouldBe NOT_FOUND
    }

    "return 401 Unauthorized when no internal auth token is provided" in {
      val result = getWithoutAuthorization(monthlyPath)

      result.status shouldBe UNAUTHORIZED
    }

    "return 401 Unauthorized when the internal auth token is invalid" in {
      val result = getWithAuthorization(monthlyPath, invalidInternalAuthToken)

      result.status shouldBe UNAUTHORIZED
    }

    "return 403 Forbidden when the internal auth token does not have permission" in {
      val result = getWithAuthorization(monthlyPath, forbiddenInternalAuthToken)

      result.status shouldBe FORBIDDEN
    }
  }

  "POST /monthly/:zReference/:taxYear/:month/declarations" should {

    "return 200 Ok when the monthly return is declared successfully" in {
      postJson(monthlyPath, nilReturnFalseRequest).status shouldBe CREATED
      val result = postJson(declarationPath, Json.obj("nilReturn" -> false))

      result.status shouldBe OK
    }

    "create pending submissions with CREATED status when declaring" in {
      postJson(monthlyPath, nilReturnFalseRequest).status shouldBe CREATED

      val result = postJson(
        declarationPath,
        Json.obj(
          "nilReturn"            -> false,
          "pendingSubmissionIds" -> Json.arr(testUploadReference)
        )
      )

      result.status shouldBe OK
      val submissions = (get(monthlyPath).json \ "submissions").as[Seq[JsValue]]
      submissions.size shouldBe 1
      (submissions.head \ "reference").as[String] shouldBe testUploadReference
      (submissions.head \ "status").as[String] shouldBe "CREATED"
      (submissions.head \ "submissionDetails").toOption shouldBe None
    }

    "return 400 BadRequest when pending submission IDs are supplied for a nil return" in {
      val result = postJson(
        declarationPath,
        Json.obj(
          "nilReturn"            -> true,
          "pendingSubmissionIds" -> Json.arr(testUploadReference)
        )
      )

      result.status shouldBe BAD_REQUEST
    }

    "return 422 UnprocessableEntity when the monthly return was already declared" in {
      postJson(monthlyPath, nilReturnFalseRequest).status shouldBe CREATED
      postJson(declarationPath,Json.obj("nilReturn" -> false)).status shouldBe OK
      val result = postJson(declarationPath, Json.obj("nilReturn" -> false))

      result.status shouldBe UNPROCESSABLE_ENTITY
    }

    "return 404 NotFound when the monthly return wasn't previously created" in {

      val result = postJson(declarationPath, Json.obj("nilReturn" -> false))

      result.status shouldBe NOT_FOUND
    }

    "return 422 UnprocessableEntity when the monthly return is not for the previous monthly period" in {
      val result = postJson(currentMonthDeclarationPath, Json.obj("nilReturn" -> false))

      result.status shouldBe UNPROCESSABLE_ENTITY
    }

    "return 401 Unauthorized when no internal auth token is provided" in {
      val result = postJsonWithoutAuthorization(declarationPath, Json.obj("nilReturn" -> false))

      result.status shouldBe UNAUTHORIZED
    }

    "return 401 Unauthorized when the internal auth token is invalid" in {
      val result = postJsonWithAuthorization(declarationPath, Json.obj("nilReturn" -> false), invalidInternalAuthToken)

      result.status shouldBe UNAUTHORIZED
    }

    "return 403 Forbidden when the internal auth token does not have permission" in {
      val result = postJsonWithAuthorization(declarationPath, Json.obj("nilReturn" -> false), forbiddenInternalAuthToken)

      result.status shouldBe FORBIDDEN
    }
  }

  "PUT /monthly/:zReference/:taxYear/:month/submissions/:submissionId" should {

    "create a missing submission with STORED status" in {

      postJson(monthlyPath, nilReturnFalseRequest)

      stubObjectStorePut()

      val result = putString(submissionPath, ndjsonContent, "application/x-ndjson")

      result.status shouldBe OK
      val submissions = (get(monthlyPath).json \ "submissions").as[Seq[JsValue]]
      submissions.size shouldBe 1
      (submissions.head \ "reference").as[String] shouldBe testUploadReference
      (submissions.head \ "status").as[String] shouldBe "STORED"
      (submissions.head \ "submissionDetails" \ "objectStoreFileLocation").as[String] should not be empty
    }

    "update an existing CREATED submission to STORED after declaration" in {
      postJson(monthlyPath, nilReturnFalseRequest).status shouldBe CREATED
      postJson(
        declarationPath,
        Json.obj(
          "nilReturn"            -> false,
          "pendingSubmissionIds" -> Json.arr(testUploadReference)
        )
      ).status shouldBe OK
      stubObjectStorePut()

      val result = putString(submissionPath, ndjsonContent, "application/x-ndjson")

      result.status shouldBe OK
      val submission = (get(monthlyPath).json \ "submissions").as[Seq[JsValue]].head
      (submission \ "status").as[String] shouldBe "STORED"
      (submission \ "submissionDetails").toOption should not be empty
    }

    "return 409 Conflict when the submission is already STORED" in {
      postJson(monthlyPath, nilReturnFalseRequest).status shouldBe CREATED
      stubObjectStorePut()
      putString(submissionPath, ndjsonContent, "application/x-ndjson").status shouldBe OK

      val result = putString(submissionPath, ndjsonContent, "application/x-ndjson")

      result.status shouldBe CONFLICT
    }

    "return 409 Conflict for an unknown submission after declaration" in {
      postJson(monthlyPath, nilReturnFalseRequest).status shouldBe CREATED
      postJson(declarationPath, Json.obj("nilReturn" -> false)).status shouldBe OK
      stubObjectStorePut()

      val result = putString(submissionPath, ndjsonContent, "application/x-ndjson")

      result.status shouldBe CONFLICT
    }

    "return 503 ServiceUnavailable when the object-store upload fails" in {

      postJson(monthlyPath, nilReturnFalseRequest)

      stubObjectStorePutInternalServerError()

      val result = putString(submissionPath, ndjsonContent, "application/x-ndjson")

      result.status shouldBe SERVICE_UNAVAILABLE
    }

    "return 404 NotFound when the monthly return couldn't be found" in {

      stubObjectStorePut()

      val result = putString(submissionPath, ndjsonContent, "application/x-ndjson")

      result.status shouldBe NOT_FOUND
    }

    "return 400 BadRequest when the body is empty" in {

      postJson(monthlyPath, nilReturnFalseRequest)

      stubObjectStorePut()

      val result = putString(submissionPath, "", "application/x-ndjson")

      result.status shouldBe BAD_REQUEST
    }

    "return 401 Unauthorized when no internal auth token is provided" in {
      val result = putStringWithoutAuthorization(submissionPath, ndjsonContent, "application/x-ndjson")

      result.status shouldBe UNAUTHORIZED
    }

    "return 401 Unauthorized when the internal auth token is invalid" in {
      val result =
        putStringWithAuthorization(submissionPath, ndjsonContent, "application/x-ndjson", invalidInternalAuthToken)

      result.status shouldBe UNAUTHORIZED
    }

    "return 403 Forbidden when the internal auth token does not have permission" in {
      val result =
        putStringWithAuthorization(submissionPath, ndjsonContent, "application/x-ndjson", forbiddenInternalAuthToken)

      result.status shouldBe FORBIDDEN
    }
  }
}
