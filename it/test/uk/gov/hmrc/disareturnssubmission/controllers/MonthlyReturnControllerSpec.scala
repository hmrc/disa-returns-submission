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


import play.api.http.Status.{BAD_REQUEST, CONFLICT, CREATED, NOT_FOUND, OK, UNPROCESSABLE_ENTITY}
import play.api.libs.json.JsValue
import uk.gov.hmrc.disareturnssubmission.BaseIntegrationSpec
import org.scalatest.wordspec.AnyWordSpec

class MonthlyReturnControllerSpec extends BaseIntegrationSpec {

  private val monthlyPath = s"$testServicePath/monthly/$testZReference/$testTaxYear/$testMonth"
  private val invalidMonthlyPath =
    s"$testServicePath/monthly/$invalidTestZReference/$invalidTestTaxYear/$invalidTestMonth"

  private val declarationPath = monthlyPath ++ "/declarations"


  "POST /monthly/:zReference/:taxYear/:month" should {

    "return 201 Created when the monthly return is created successfully" in {
      val result = postJson(monthlyPath, nilReturnFalseRequest)
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

  }

  "POST /monthly/:zReference/:taxYear/:month/declarations" should {

    "return 200 Ok when the monthly return is declared successfully" in {
      postJson(monthlyPath, nilReturnFalseRequest).status shouldBe CREATED
      val result = postJson(declarationPath)

      result.status shouldBe OK
    }

    "return 422 UnprocessableEntity when the monthly return was already declared" in {
      postJson(monthlyPath, nilReturnFalseRequest).status shouldBe CREATED
      postJson(declarationPath).status shouldBe OK
      val result = postJson(declarationPath)

      result.status shouldBe UNPROCESSABLE_ENTITY
    }

    "return 404 NotFound when the monthly return wasn't previously created" in {

      val result = postJson(declarationPath)

      result.status shouldBe NOT_FOUND
    }
  }
}
