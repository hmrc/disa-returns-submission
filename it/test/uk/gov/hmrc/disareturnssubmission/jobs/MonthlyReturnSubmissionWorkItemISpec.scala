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

import ch.qos.logback.classic.Level.*
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.stubbing.Scenario
import org.scalatest.concurrent.Eventually
import play.api.Logger
import play.api.http.HeaderNames.*
import play.api.http.MimeTypes.JSON
import play.api.http.Status.*
import play.api.libs.json.*
import play.api.test.Helpers.await
import uk.gov.hmrc.disareturnssubmission.BaseIntegrationSpec
import uk.gov.hmrc.disareturnssubmission.models.monthly.*
import uk.gov.hmrc.disareturnssubmission.repositories.*
import uk.gov.hmrc.disareturnssubmission.utils.Constants.NDJSON_MIME_TYPE
import uk.gov.hmrc.disareturnssubmission.utils.ObjectStoreWireMockStubs
import uk.gov.hmrc.mongo.workitem.*
import uk.gov.hmrc.play.bootstrap.tools.LogCapturing

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.*
import java.time.Clock
import scala.jdk.CollectionConverters.*

class MonthlyReturnSubmissionWorkItemISpec
    extends BaseIntegrationSpec
    with ObjectStoreWireMockStubs
    with Eventually
    with LogCapturing {
  private lazy val maxRecordsPerSubmission = 2500

  override protected def clock: Clock   = Clock.systemUTC()
  override def config: Map[String, Any] = super.config ++ Map(
    "mongodb.uri"                                              -> "mongodb://localhost:27017/disa-returns-submission-work-item-it",
    "monthly-return-submission-work-item-job.enabled"          -> true,
    "monthly-return-submission-work-item-job.pollInterval"     -> "50 milliseconds",
    "monthly-return-submission-work-item-job.failedRetryAfter" -> "2 seconds",
    "monthly-return-submission-work-item-job.workerCount"      -> 1,
    "monthly-return.max-records-per-submission"                -> maxRecordsPerSubmission
  )

  private val monthlyPath             = s"$testServicePath/monthly/$testZReference/$testTaxYear/$testMonth"
  private val submissionPath          = s"$monthlyPath/submissions/$testUploadReference"
  private val npsPath                 = s"/nps/submit/$testZReference"
  private val objectStorePath         = s"/object-store/object/disa-returns-submission/$testUploadReference"
  private val connectorAttemptCount   = 4
  private val multiBatchRecordCount   = 3000
  private val lastBatchRecordCount    = multiBatchRecordCount - maxRecordsPerSubmission
  private val secondBatchFirstAccount = s"BATCH${maxRecordsPerSubmission + 1}"
  private val retryCheckDelayMillis   = 200L
  private val npsBearerToken          = "nps-stub-token"
  private val recoveredState          = "recovered"

  "submission work item" should {
    "download, convert and POST a JSON array, then retain the succeeded item without resending" in {
      stubNps(NO_CONTENT)
      submit()

      eventually {
        workItem.status  shouldBe ProcessingStatus.Succeeded
        submissionStatus shouldBe MonthlyReturnSubmissionStatus.NpsSubmitted
      }
      verify(
        1,
        postRequestedFor(urlEqualTo(npsPath))
          .withHeader(CONTENT_TYPE, containing(JSON))
          .withHeader(AUTHORIZATION, equalTo(s"Bearer $npsBearerToken"))
          .withRequestBody(equalToJson(npsSubmissionJson.toString))
      )

      putString(submissionPath, ndjsonContent, NDJSON_MIME_TYPE).status                           shouldBe CONFLICT
      await(inject[MonthlyReturnSubmissionWorkItemRepository].collection.countDocuments().head()) shouldBe 1L
      verify(1, postRequestedFor(urlEqualTo(npsPath)))
    }

    Seq("backend", "api").foreach { producer =>
      s"transfer all four $producer variants using the shared submission schema" in {
        val input    = Files.readString(fixture(producer), UTF_8)
        val expected = Files.readString(fixture("backend"), UTF_8).linesIterator.mkString("[", ",", "]")
        stubNps(NO_CONTENT)
        submit(input)

        eventually {
          workItem.status  shouldBe ProcessingStatus.Succeeded
          submissionStatus shouldBe MonthlyReturnSubmissionStatus.NpsSubmitted
        }
        verify(1, postRequestedFor(urlEqualTo(npsPath)).withRequestBody(equalToJson(expected)))
      }
    }

    Seq(
      maxRecordsPerSubmission       -> Seq(maxRecordsPerSubmission),
      multiBatchRecordCount         -> Seq(maxRecordsPerSubmission, lastBatchRecordCount),
      (2 * maxRecordsPerSubmission) -> Seq(maxRecordsPerSubmission, maxRecordsPerSubmission)
    ).foreach { (count, batchSizes) =>
      s"send $count records in ordered $batchSizes batches with a $maxRecordsPerSubmission configuration override" in {
        stubNps(NO_CONTENT)
        submit(generatedRecords(count))

        eventually {
          workItem.status  shouldBe ProcessingStatus.Succeeded
          submissionStatus shouldBe MonthlyReturnSubmissionStatus.NpsSubmitted
        }
        npsBatches.map(_.size) shouldBe batchSizes
        npsBatches.flatten     shouldBe (1 to count).map(index => s"BATCH$index")
      }
    }

    "leave an invalid later batch Failed without sending any NPS requests" in {
      stubNps(NO_CONTENT)
      submit(generatedRecords(multiBatchRecordCount) + "{\"id\":1}\n")

      eventually {
        workItem.status shouldBe ProcessingStatus.Failed
      }
      submissionStatus shouldBe MonthlyReturnSubmissionStatus.Stored
      verify(0, postRequestedFor(urlEqualTo(npsPath)))
    }

    "retry a transient NPS 5xx with the complete JSON file without retrying the work item" in {
      val scenarioName = "NPS recovery"
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
      submit()

      eventually {
        workItem.status       shouldBe ProcessingStatus.Succeeded
        workItem.failureCount shouldBe 0
        submissionStatus      shouldBe MonthlyReturnSubmissionStatus.NpsSubmitted
      }
      verify(
        2,
        postRequestedFor(urlEqualTo(npsPath))
          .withRequestBody(equalToJson(npsSubmissionJson.toString))
      )
      verify(1, getRequestedFor(urlEqualTo(objectStorePath)))
    }

    "retry only a transiently failed later batch with its full body before marking the work item succeeded" in {
      stubNps(NO_CONTENT)
      stubFor(
        post(urlEqualTo(npsPath))
          .atPriority(1)
          .withRequestBody(matchingJsonPath("$[0].accountNumber", equalTo(secondBatchFirstAccount)))
          .inScenario("Later batch recovery")
          .whenScenarioStateIs(Scenario.STARTED)
          .willReturn(aResponse().withStatus(INTERNAL_SERVER_ERROR))
          .willSetStateTo(recoveredState)
      )
      submit(generatedRecords(multiBatchRecordCount))

      eventually {
        workItem.status       shouldBe ProcessingStatus.Succeeded
        workItem.failureCount shouldBe 0
        submissionStatus      shouldBe MonthlyReturnSubmissionStatus.NpsSubmitted
      }
      val batches = npsBatches
      batches.map(_.size)     shouldBe Seq(maxRecordsPerSubmission, lastBatchRecordCount, lastBatchRecordCount)
      batches.take(2).flatten shouldBe (1 to multiBatchRecordCount).map(index => s"BATCH$index")
      batches(1)              shouldBe batches(2)
      verify(1, getRequestedFor(urlEqualTo(objectStorePath)))
    }

    Seq(SERVICE_UNAVAILABLE, BAD_REQUEST).foreach { status =>
      s"stop after a later batch fails with $status, delay the work item and replay every batch on retry" in {
        val recordCount  = 6000
        stubNps(NO_CONTENT)
        val failingBatch = stubFor(
          post(urlEqualTo(npsPath))
            .atPriority(1)
            .withRequestBody(matchingJsonPath("$[0].accountNumber", equalTo(secondBatchFirstAccount)))
            .willReturn(aResponse().withStatus(status))
        )
        submit(generatedRecords(recordCount))

        eventually {
          workItem.status       shouldBe ProcessingStatus.Failed
          workItem.failureCount shouldBe 1
        }
        submissionStatus shouldBe MonthlyReturnSubmissionStatus.Stored
        val attempts      = if (status == SERVICE_UNAVAILABLE) connectorAttemptCount else 1
        Thread.sleep(retryCheckDelayMillis)
        val failedBatches = npsBatches
        failedBatches.map(_.size)        shouldBe Seq.fill(attempts + 1)(maxRecordsPerSubmission)
        failedBatches.head.head          shouldBe "BATCH1"
        failedBatches.tail.foreach(batch => batch.head shouldBe secondBatchFirstAccount)
        failedBatches.tail.distinct.size shouldBe 1
        verify(1, getRequestedFor(urlEqualTo(objectStorePath)))
        removeStub(failingBatch)

        eventually {
          workItem.status       shouldBe ProcessingStatus.Succeeded
          workItem.failureCount shouldBe 1
          submissionStatus      shouldBe MonthlyReturnSubmissionStatus.NpsSubmitted
        }
        val replay = npsBatches.drop(attempts + 1)
        replay.map(_.size) shouldBe Seq(
          maxRecordsPerSubmission,
          maxRecordsPerSubmission,
          recordCount - 2 * maxRecordsPerSubmission
        )
        replay.flatten     shouldBe (1 to recordCount).map(index => s"BATCH$index")
        verify(2, getRequestedFor(urlEqualTo(objectStorePath)))
      }
    }

    "exhaust NPS connector retries before marking Failed, then succeed on a later work-item pull" in {
      stubFor(
        post(urlEqualTo(npsPath)).willReturn(
          aResponse().withStatus(SERVICE_UNAVAILABLE).withBody("private NPS response: AB123456C")
        )
      )
      withCaptureOfLoggingFrom(Logger("application")) { retryEvents =>
        withCaptureOfLoggingFrom(Logger(inject[MonthlyReturnSubmissionWorkItemJob].getClass)) { jobEvents =>
          submit()

          eventually {
            workItem.status       shouldBe ProcessingStatus.Failed
            workItem.failureCount shouldBe 1
          }
          val retries = retryEvents.filter(event =>
            event.getLevel == WARN && event.getMessage.contains(
              "Retrying [NpsConnector][sendSubmission] send NPS submission"
            )
          )
          retries.size                       shouldBe connectorAttemptCount - 1
          retries.foreach { event =>
            event.getMessage should include(s"zReference [$testZReference]")
            event.getMessage should include(s"status [$SERVICE_UNAVAILABLE]")
          }
          val failure = jobEvents.filter(_.getLevel == ERROR).last
          failure.getThrowableProxy.getMessage should include(s"status [$SERVICE_UNAVAILABLE]")
          val diagnostics = (retries.map(_.getMessage) :+ failure.getThrowableProxy.getMessage).mkString
          diagnostics should not include "private NPS response"
          diagnostics should not include "AB123456C"
          diagnostics should not include npsBearerToken
        }
      }
      submissionStatus shouldBe MonthlyReturnSubmissionStatus.Stored
      Thread.sleep(retryCheckDelayMillis)
      verify(connectorAttemptCount, postRequestedFor(urlEqualTo(npsPath)))
      stubNps(NO_CONTENT)

      eventually {
        workItem.status  shouldBe ProcessingStatus.Succeeded
        submissionStatus shouldBe MonthlyReturnSubmissionStatus.NpsSubmitted
      }
      verify(connectorAttemptCount + 1, postRequestedFor(urlEqualTo(npsPath)))
    }

    Seq(BAD_REQUEST, TOO_MANY_REQUESTS).foreach { status =>
      s"leave NPS $status responses for delayed work-item retry without connector retries" in {
        stubNps(status)
        submit()

        eventually {
          workItem.status       shouldBe ProcessingStatus.Failed
          workItem.failureCount shouldBe 1
        }
        submissionStatus shouldBe MonthlyReturnSubmissionStatus.Stored
        Thread.sleep(retryCheckDelayMillis)
        verify(1, postRequestedFor(urlEqualTo(npsPath)))
      }
    }
  }

  private def fixture(producer: String): Path =
    Path.of(getClass.getResource(s"/monthly-return-submissions/$producer.ndjson").toURI)

  private def submit(content: String = ndjsonContent): Unit = {
    stubObjectStorePut()
    stubObjectStoreGet(content)
    postJson(monthlyPath, nilReturnFalseRequest).status         shouldBe CREATED
    putString(submissionPath, content, NDJSON_MIME_TYPE).status shouldBe OK
  }

  private def stubNps(status: Int): Unit =
    stubFor(post(urlEqualTo(npsPath)).willReturn(aResponse().withStatus(status)))

  private def generatedRecords(count: Int): String = {
    val record = Json.parse(ndjsonContent.linesIterator.next()).as[JsObject]
    (1 to count).iterator
      .map(index => Json.stringify(record + ("accountNumber" -> JsString(s"BATCH$index"))))
      .mkString("", "\n", "\n")
  }

  private def npsBatches: Seq[Seq[String]] =
    findAll(postRequestedFor(urlEqualTo(npsPath))).asScala.toSeq.sortBy(_.getLoggedDate.getTime).map { request =>
      Json.parse(request.getBodyAsString).as[JsArray].value.map(record => (record \ "accountNumber").as[String]).toSeq
    }

  private def workItem: WorkItem[MonthlyReturnSubmissionWorkItem] =
    await(inject[MonthlyReturnSubmissionWorkItemRepository].collection.find().headOption()).get

  private def submissionStatus: MonthlyReturnSubmissionStatus =
    await(inject[MonthlyReturnRepository].get(testZReference, testTaxYear, testMonth)).get.submissions.head.status
}
