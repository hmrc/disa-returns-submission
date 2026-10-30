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

class MonthlyReturnSubmissionWorkItemISpec
    extends BaseIntegrationSpec
    with ObjectStoreWireMockStubs
    with Eventually
    with LogCapturing {
  override protected def clock: Clock   = Clock.systemUTC()
  override def config: Map[String, Any] = super.config ++ Map(
    "mongodb.uri"                                              -> "mongodb://localhost:27017/disa-returns-submission-work-item-it",
    "monthly-return-submission-work-item-job.enabled"          -> true,
    "monthly-return-submission-work-item-job.pollInterval"     -> "50 milliseconds",
    "monthly-return-submission-work-item-job.failedRetryAfter" -> "2 seconds",
    "monthly-return-submission-work-item-job.workerCount"      -> 1
  )

  private val monthlyPath           = s"$testServicePath/monthly/$testZReference/$testTaxYear/$testMonth"
  private val submissionPath        = s"$monthlyPath/submissions/$testUploadReference"
  private val npsPath               = s"/nps/submit/$testZReference"
  private val connectorAttemptCount = 4

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
          .withHeader(AUTHORIZATION, equalTo("Bearer nps-stub-token"))
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

    "leave invalid model records Failed without sending a partial NPS request" in {
      stubNps(NO_CONTENT)
      submit(ndjsonContent + "{\"id\":1}\n")

      eventually {
        workItem.status shouldBe ProcessingStatus.Failed
      }
      submissionStatus shouldBe MonthlyReturnSubmissionStatus.Stored
      verify(0, postRequestedFor(urlEqualTo(npsPath)))
    }

    "retry a transient NPS 5xx with the complete JSON file without retrying the work item" in {
      stubFor(
        post(urlEqualTo(npsPath))
          .inScenario("NPS recovery")
          .whenScenarioStateIs(Scenario.STARTED)
          .willReturn(aResponse().withStatus(INTERNAL_SERVER_ERROR))
          .willSetStateTo("recovered")
      )
      stubFor(
        post(urlEqualTo(npsPath))
          .inScenario("NPS recovery")
          .whenScenarioStateIs("recovered")
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
      verify(1, getRequestedFor(urlEqualTo(s"/object-store/object/disa-returns-submission/$testUploadReference")))
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
            event.getLevel == WARN && event.getMessage.contains("Retrying send NPS submission")
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
          diagnostics should not include "nps-stub-token"
        }
      }
      submissionStatus shouldBe MonthlyReturnSubmissionStatus.Stored
      Thread.sleep(200)
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
        Thread.sleep(200)
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

  private def workItem: WorkItem[MonthlyReturnSubmissionWorkItem] =
    await(inject[MonthlyReturnSubmissionWorkItemRepository].collection.find().headOption()).get

  private def submissionStatus: MonthlyReturnSubmissionStatus =
    await(inject[MonthlyReturnRepository].get(testZReference, testTaxYear, testMonth)).get.submissions.head.status
}
