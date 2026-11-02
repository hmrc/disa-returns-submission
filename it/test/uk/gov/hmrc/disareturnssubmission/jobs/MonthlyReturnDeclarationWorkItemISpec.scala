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

package uk.gov.hmrc.disareturnssubmission.jobs

import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.stubbing.Scenario
import org.scalatest.concurrent.Eventually
import play.api.http.HeaderNames.AUTHORIZATION
import play.api.http.Status.*
import play.api.libs.json.Json
import play.api.test.Helpers.await
import uk.gov.hmrc.disareturnssubmission.BaseIntegrationSpec
import uk.gov.hmrc.disareturnssubmission.config.AppConfig
import uk.gov.hmrc.disareturnssubmission.models.monthly.*
import uk.gov.hmrc.disareturnssubmission.repositories.*
import uk.gov.hmrc.mongo.workitem.*

import java.time.*
import java.util.concurrent.atomic.AtomicReference

class MonthlyReturnDeclarationWorkItemISpec extends BaseIntegrationSpec with Eventually {
  private lazy val currentInstant     = new AtomicReference(integrationTestNow)
  override protected def clock: Clock = new Clock {
    override def getZone: ZoneId               = ZoneOffset.UTC
    override def withZone(zone: ZoneId): Clock = this
    override def instant(): Instant            = currentInstant.get()
  }

  override def config: Map[String, Any] = super.config ++ Map(
    "mongodb.uri"                                           -> "mongodb://localhost:27017/disa-returns-submission-declaration-work-item-it",
    "monthly-return-declaration-work-item-job.enabled"      -> true,
    "monthly-return-declaration-work-item-job.pollInterval" -> "50 milliseconds",
    "monthly-return-declaration-work-item-job.workerCount"  -> 1
  )

  private val monthlyPath           = s"$testServicePath/monthly/$testZReference/$testTaxYear/$testMonth"
  private val declarationPath       = s"$monthlyPath/declarations"
  private val npsPath               = s"/nps/declaration/$testZReference"
  private val npsBearerToken        = "nps-stub-token"
  private val recoveredState        = "recovered"
  private val connectorAttemptCount = 4
  private lazy val repository       = inject[MonthlyReturnDeclarationWorkItemRepository]
  private lazy val returns          = inject[MonthlyReturnRepository]
  private lazy val appConfig        = inject[AppConfig]

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    currentInstant.set(integrationTestNow)
  }

  "declaration work item" should {
    "declare an NPS_SUBMITTED return, persist the timestamp and retain the succeeded work item" in {
      stubNps(NO_CONTENT)
      queueDeclaration(Seq.fill(2)(MonthlyReturnSubmissionStatus.NpsSubmitted))
      advanceClock()

      eventually {
        workItem.status                                        shouldBe ProcessingStatus.Succeeded
        monthlyReturnRecord.npsDeclaredOn                      shouldBe Some(currentInstant.get())
        monthlyReturnRecord.submissions.map(_.status).distinct shouldBe List(MonthlyReturnSubmissionStatus.NpsSubmitted)
      }
      verify(1, postRequestedFor(urlEqualTo(npsPath)).withHeader(AUTHORIZATION, equalTo(s"Bearer $npsBearerToken")))
      (get(monthlyPath).json \ "npsDeclaredOn").as[String] shouldBe currentInstant.get().toString

      postJson(declarationPath, nilReturnFalseRequest).status shouldBe UNPROCESSABLE_ENTITY
      await(repository.collection.countDocuments().head())    shouldBe 1L
      verify(1, postRequestedFor(urlEqualTo(npsPath)))
    }

    Seq(false, true).foreach { nilReturn =>
      s"declare an empty monthly return with nilReturn [$nilReturn]" in {
        stubNps(NO_CONTENT)
        queueDeclaration(nilReturn = nilReturn)
        advanceClock()

        eventually {
          workItem.status                   shouldBe ProcessingStatus.Succeeded
          monthlyReturnRecord.npsDeclaredOn shouldBe Some(currentInstant.get())
        }
        verify(1, postRequestedFor(urlEqualTo(npsPath)))
      }
    }

    Seq(MonthlyReturnSubmissionStatus.Created, MonthlyReturnSubmissionStatus.Stored).foreach { status =>
      s"wait for a [$status] submission without a failure and declare only after the configured availableAt" in {
        stubNps(NO_CONTENT)
        queueDeclaration(Seq(MonthlyReturnSubmissionStatus.NpsSubmitted, status))
        advanceClock()

        eventually {
          workItem.status      shouldBe ProcessingStatus.ToDo
          workItem.availableAt shouldBe currentInstant
            .get()
            .plus(appConfig.monthlyReturnDeclarationJobPendingSubmissionsRetryAfter)
        }
        val deferred = workItem
        deferred.failureCount             shouldBe 0
        monthlyReturnRecord.npsDeclaredOn shouldBe None
        verify(0, postRequestedFor(urlEqualTo(npsPath)))

        val pending = monthlyReturnRecord.submissions.last
        if (status == MonthlyReturnSubmissionStatus.Created) {
          await(
            returns.storeSubmission(testZReference, testTaxYear, testMonth, pending.reference, fileUploadDetails)
          ) shouldBe StoreSubmissionRepositoryResult.SubmissionStored
        }
        await(
          returns.markSubmissionNpsSubmitted(testZReference, testTaxYear, testMonth, pending.reference)
        ) shouldBe true
        currentInstant.set(deferred.availableAt.minusMillis(1))
        await(repository.pullOutstanding(currentInstant.get(), currentInstant.get())) shouldBe None
        verify(0, postRequestedFor(urlEqualTo(npsPath)))

        currentInstant.set(deferred.availableAt.plusMillis(1))
        eventually {
          workItem.status                   shouldBe ProcessingStatus.Succeeded
          workItem.failureCount             shouldBe 0
          monthlyReturnRecord.npsDeclaredOn shouldBe Some(currentInstant.get())
        }
        verify(1, postRequestedFor(urlEqualTo(npsPath)))
      }
    }

    "skip NPS when a previous attempt persisted npsDeclaredOn before completing the work item" in {
      queueDeclaration()
      await(returns.markNpsDeclared(testZReference, testTaxYear, testMonth)) shouldBe true
      advanceClock()

      eventually {
        workItem.status                   shouldBe ProcessingStatus.Succeeded
        monthlyReturnRecord.npsDeclaredOn shouldBe Some(integrationTestNow)
      }
      verify(0, postRequestedFor(urlEqualTo(npsPath)))
    }

    "recover a transient NPS 5xx inside the connector without failing the work item" in {
      val scenarioName = "NPS declaration recovery"
      stubFor(
        post(urlEqualTo(npsPath))
          .inScenario(scenarioName)
          .whenScenarioStateIs(Scenario.STARTED)
          .willReturn(aResponse().withStatus(INTERNAL_SERVER_ERROR))
          .willSetStateTo(recoveredState)
      )
      stubFor(
        post(urlEqualTo(npsPath))
          .inScenario(scenarioName)
          .whenScenarioStateIs(recoveredState)
          .willReturn(aResponse().withStatus(NO_CONTENT))
      )
      queueDeclaration()
      advanceClock()

      eventually {
        workItem.status                   shouldBe ProcessingStatus.Succeeded
        workItem.failureCount             shouldBe 0
        monthlyReturnRecord.npsDeclaredOn shouldBe Some(currentInstant.get())
      }
      verify(2, postRequestedFor(urlEqualTo(npsPath)))
    }

    Seq(BAD_REQUEST, TOO_MANY_REQUESTS, INTERNAL_SERVER_ERROR, SERVICE_UNAVAILABLE).foreach { status =>
      s"leave an NPS [$status] declaration Failed until failedRetryAfter, then recover" in {
        stubNps(status)
        queueDeclaration()
        advanceClock()

        eventually {
          workItem.status       shouldBe ProcessingStatus.Failed
          workItem.failureCount shouldBe 1
        }
        monthlyReturnRecord.npsDeclaredOn shouldBe None
        val attempts = if (status >= INTERNAL_SERVER_ERROR) {
          connectorAttemptCount
        } else {
          1
        }
        verify(attempts, postRequestedFor(urlEqualTo(npsPath)))
        val retryAt  = currentInstant.get().plus(appConfig.monthlyReturnDeclarationJobFailedRetryAfter)
        currentInstant.set(retryAt.minusMillis(1))
        await(
          repository.pullOutstanding(
            currentInstant.get().minus(appConfig.monthlyReturnDeclarationJobFailedRetryAfter),
            currentInstant.get()
          )
        ) shouldBe None
        verify(attempts, postRequestedFor(urlEqualTo(npsPath)))

        stubNps(NO_CONTENT)
        currentInstant.set(retryAt.plusMillis(1))
        eventually {
          workItem.status                   shouldBe ProcessingStatus.Succeeded
          workItem.failureCount             shouldBe 1
          monthlyReturnRecord.npsDeclaredOn shouldBe Some(currentInstant.get())
        }
        verify(attempts + 1, postRequestedFor(urlEqualTo(npsPath)))
      }
    }
  }

  private def queueDeclaration(statuses: Seq[MonthlyReturnSubmissionStatus] = Nil, nilReturn: Boolean = false): Unit = {
    val references = statuses.indices.map(index => s"$testUploadReference-$index")
    postJson(monthlyPath, Json.obj(nilReturnFieldName -> nilReturn)).status shouldBe CREATED
    postJson(
      declarationPath,
      Json.obj(nilReturnFieldName -> nilReturn, "pendingSubmissionIds" -> references)
    ).status                                                                shouldBe OK
    statuses.zip(references).foreach { (status, reference) =>
      if (status != MonthlyReturnSubmissionStatus.Created) {
        await(
          returns.storeSubmission(testZReference, testTaxYear, testMonth, reference, fileUploadDetails)
        ) shouldBe StoreSubmissionRepositoryResult.SubmissionStored
        if (status == MonthlyReturnSubmissionStatus.NpsSubmitted) {
          await(returns.markSubmissionNpsSubmitted(testZReference, testTaxYear, testMonth, reference)) shouldBe true
        }
      }
    }
  }

  private def advanceClock(): Unit =
    currentInstant.set(currentInstant.get().plusMillis(1))

  private def stubNps(status: Int): Unit =
    stubFor(post(urlEqualTo(npsPath)).willReturn(aResponse().withStatus(status)))

  private def workItem: WorkItem[MonthlyReturnDeclarationWorkItem] =
    await(repository.collection.find().headOption()).get

  private def monthlyReturnRecord: MonthlyReturn =
    await(returns.get(testZReference, testTaxYear, testMonth)).get
}
