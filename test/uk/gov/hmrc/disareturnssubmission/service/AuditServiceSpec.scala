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

package uk.gov.hmrc.disareturnssubmission.service

import base.SpecBase
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{verify, when}
import play.api.libs.json.{JsObject, Json}
import play.api.test.Helpers.*
import uk.gov.hmrc.disareturnssubmission.config.AppConfig
import uk.gov.hmrc.disareturnssubmission.services.{AuditService, AuditTypes, EventData, SubmissionStatus}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.audit.http.connector.AuditResult.{Disabled, Failure, Success}
import uk.gov.hmrc.play.audit.model.ExtendedDataEvent

import scala.concurrent.Future

class AuditServiceSpec extends SpecBase {

  private implicit val hc: HeaderCarrier = HeaderCarrier()

  "AuditService.auditNpsDeclarationSubmitted" - {

    "must send NPSDeclarationSubmitted with a success status" in new TestSetup {
      await(service.auditNpsDeclarationSubmitted(monthlyReturn, None))

      val event = captureEvent()

      event.auditSource mustBe "disa-returns-submission"
      event.auditType mustBe AuditTypes.NpsDeclarationSubmitted
      event.detail mustBe Json.obj(
        EventData.internalReturnId -> monthlyReturn.submissionId.toString,
        EventData.period           -> "May 2026",
        EventData.submissionStatus -> SubmissionStatus.Success
      )
    }

    "must send NPSDeclarationSubmitted with a failure status and reason" in new TestSetup {
      await(service.auditNpsDeclarationSubmitted(monthlyReturn, Some("NPS declaration returned status [500]")))

      captureEvent().detail mustBe Json.obj(
        EventData.internalReturnId -> monthlyReturn.submissionId.toString,
        EventData.period           -> "May 2026",
        EventData.submissionStatus -> SubmissionStatus.Failure,
        EventData.failureReason    -> "NPS declaration returned status [500]"
      )
    }

    Seq(
      ("2025-26", 4, "April 2025"),
      ("2025-26", 12, "December 2025"),
      ("2025-26", 1, "January 2026"),
      ("2025-26", 3, "March 2026")
    ).foreach { case (taxYear, month, expected) =>
      s"must report tax year [$taxYear] month [$month] as period [$expected]" in new TestSetup {
        await(service.auditNpsDeclarationSubmitted(monthlyReturn.copy(taxYear = taxYear, month = month), None))

        (captureEvent().detail.as[JsObject] \ EventData.period).as[String] mustBe expected
      }
    }

    Seq(Failure("audit error"), Disabled).foreach { result =>
      s"must complete when the audit result is [$result]" in new TestSetup {
        when(auditConnector.sendExtendedEvent(any[ExtendedDataEvent])(any(), any()))
          .thenReturn(Future.successful(result))

        await(service.auditNpsDeclarationSubmitted(monthlyReturn, None)) mustBe ()
      }
    }
  }

  private trait TestSetup {
    val auditConnector: AuditConnector = mock[AuditConnector]
    val appConfig: AppConfig           = mock[AppConfig]
    val service                        = new AuditService(auditConnector, appConfig)

    when(appConfig.appName).thenReturn("disa-returns-submission")
    when(auditConnector.sendExtendedEvent(any[ExtendedDataEvent])(any(), any()))
      .thenReturn(Future.successful(Success))

    def captureEvent(): ExtendedDataEvent = {
      val captor: ArgumentCaptor[ExtendedDataEvent] = ArgumentCaptor.forClass(classOf[ExtendedDataEvent])
      verify(auditConnector).sendExtendedEvent(captor.capture())(any(), any())
      captor.getValue
    }
  }
}
