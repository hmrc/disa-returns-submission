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

import org.mongodb.scala.ObservableFuture
import play.api.http.Status.{BAD_REQUEST, NO_CONTENT}
import play.api.libs.json.Json
import play.api.test.Helpers.await
import uk.gov.hmrc.disareturnssubmission.BaseIntegrationSpec
import uk.gov.hmrc.disareturnssubmission.models.monthly.*
import uk.gov.hmrc.disareturnssubmission.repositories.*

import java.time.Clock

class TestOnlyMonthlyReturnCleanupISpec extends BaseIntegrationSpec {

  override protected def clock: Clock = Clock.systemUTC()

  override def config: Map[String, Any] = super.config ++ Map(
    "application.router" -> "testOnlyDoNotUseInAppConf.Routes",
    "mongodb.uri"        -> "mongodb://localhost:27017/disa-returns-submission-cleanup-it"
  )

  private val returnsPath      = s"$testServicePath/test-only/monthly-returns"
  private val workItemsPath    = s"$returnsPath/work-items"
  private val otherZReference  = "Z5678"
  private val orphanZReference = "Z9012"

  private lazy val returns      = inject[MonthlyReturnRepository]
  private lazy val declarations = inject[MonthlyReturnDeclarationWorkItemRepository]
  private lazy val submissions  = inject[MonthlyReturnSubmissionWorkItemRepository]

  "test-only monthly return cleanup" should {

    "delete only matching returns and work items, including orphaned items" in {
      seed(testZReference)
      seed(otherZReference)
      seed(orphanZReference, withReturn = false)

      val lowerReference = s" ${testZReference.toLowerCase} "
      val body           = Json.obj("zReferences" -> Seq(lowerReference, lowerReference, orphanZReference))
      postJsonWithoutAuthorization(s"$returnsPath/cleanup", body).status shouldBe NO_CONTENT
      postJsonWithoutAuthorization(s"$returnsPath/cleanup", body).status shouldBe NO_CONTENT
      assertState(Set(otherZReference), Set(otherZReference))
    }

    "delete only matching work items without deleting returns" in {
      seed(testZReference)
      seed(otherZReference)

      val body = Json.obj("zReferences" -> Seq(testZReference))
      postJsonWithoutAuthorization(s"$workItemsPath/cleanup", body).status shouldBe NO_CONTENT
      assertState(Set(testZReference, otherZReference), Set(otherZReference))
    }

    "reject missing, empty or invalid references without deleting any records" in {
      seed(testZReference)

      val invalidBodies = Seq(
        Json.obj(),
        Json.obj("zReferences" -> Json.arr()),
        Json.obj("zReferences" -> Json.arr(testZReference, "invalid"))
      )
      for {
        path <- Seq(returnsPath, workItemsPath)
        body <- invalidBodies
      } postJsonWithoutAuthorization(s"$path/cleanup", body).status shouldBe BAD_REQUEST
      assertState(Set(testZReference), Set(testZReference))
    }

    "delete all work items without deleting returns" in {
      seed(testZReference)
      seed(orphanZReference, withReturn = false)

      await(ws.url(serviceUrl(s"$workItemsPath/")).delete()).status shouldBe NO_CONTENT
      assertState(Set(testZReference), Set.empty)
    }

    "delete all returns and work items" in {
      seed(testZReference)
      seed(orphanZReference, withReturn = false)

      await(ws.url(serviceUrl(s"$returnsPath/cleanup")).delete()).status shouldBe NO_CONTENT
      assertState(Set.empty, Set.empty)
    }
  }

  private def seed(zReference: String, withReturn: Boolean = true): Unit = {
    if (withReturn) {
      returns.create(zReference, testTaxYear, testMonth, testSubmissionId, nilReturn = false).futureValue
    }
    declarations.enqueue(MonthlyReturnDeclarationWorkItem(zReference, testTaxYear, testMonth)).futureValue
    submissions
      .enqueue(MonthlyReturnSubmissionWorkItem(zReference, testTaxYear, testMonth, testUploadReference))
      .futureValue
  }

  private def assertState(expectedReturns: Set[String], expectedWorkItems: Set[String]): Unit = {
    returns.collection.find().toFuture().futureValue.map(_.zReference).toSet           shouldBe expectedReturns
    declarations.collection.find().toFuture().futureValue.map(_.item.zReference).toSet shouldBe expectedWorkItems
    submissions.collection.find().toFuture().futureValue.map(_.item.zReference).toSet  shouldBe expectedWorkItems
  }
}
