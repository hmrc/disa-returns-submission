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
import ch.qos.logback.classic.Level.WARN
import org.mockito.ArgumentMatchers.{eq as eqTo, *}
import org.scalatest.concurrent.Eventually.eventually
import org.mockito.Mockito.{timeout as mockitoTimeout, *}
import play.api.Logger
import play.api.test.Helpers.*
import uk.gov.hmrc.disareturnssubmission.connectors.NpsConnector
import uk.gov.hmrc.disareturnssubmission.models.monthly.*
import uk.gov.hmrc.disareturnssubmission.repositories.MonthlyReturnRepository
import uk.gov.hmrc.disareturnssubmission.services.*
import uk.gov.hmrc.disareturnssubmission.services.MonthlyReturnDeclarationProcessingResult.*
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.play.bootstrap.tools.LogCapturing

import scala.concurrent.*

class MonthlyReturnDeclarationProcessingServiceSpec extends SpecBase with LogCapturing {
  private val item           = MonthlyReturnDeclarationWorkItem(testZReference, testTaxYear, testMonth)
  private val submitted      =
    MonthlyReturnSubmission(testUploadReference, MonthlyReturnSubmissionStatus.NpsSubmitted, testCreatedOn)
  private val declaredReturn =
    monthlyReturn.copy(declaredOn = Some(testExistingUpdatedOn), submissions = List(submitted))
  private val successMessage = "Declared to NPS and recorded npsDeclaredOn"
  private val updateFailure  = new RuntimeException("mongo unavailable")

  "MonthlyReturnDeclarationProcessingService" - {
    "must declare only after all submissions are NPS_SUBMITTED and await timestamp persistence" in new TestSetup {
      val marked = Promise[Boolean]()
      when(repository.markNpsDeclared(any(), any(), anyInt())).thenReturn(marked.future)

      withCaptureOfLoggingFrom(Logger(service.getClass)) { events =>
        val result = service.process(item)
        verify(repository, mockitoTimeout(1000)).markNpsDeclared(testZReference, testTaxYear, testMonth)
        verify(nps).sendDeclaration(testZReference)
        result.isCompleted mustBe false
        events.exists(_.getMessage.contains(successMessage)) mustBe false
        marked.success(true)
        await(result) mustBe Declared
        events.last.getMessage must include(successMessage)
      }
      verify(repository, never()).markSubmissionNpsSubmitted(any(), any(), anyInt(), any())
    }

    Seq(false, true).foreach { nilReturn =>
      s"must declare an empty return with nilReturn [$nilReturn] without waiting for submissions" in new TestSetup {
        when(repository.get(any(), any(), anyInt()))
          .thenReturn(Future.successful(Some(declaredReturn.copy(nilReturn = nilReturn, submissions = Nil))))

        await(service.process(item)) mustBe Declared
        verify(nps).sendDeclaration(testZReference)
        verify(repository).markNpsDeclared(testZReference, testTaxYear, testMonth)
      }
    }

    Seq(MonthlyReturnSubmissionStatus.Created, MonthlyReturnSubmissionStatus.Stored).foreach { status =>
      s"must wait without calling NPS or updating the return when any submission is [$status]" in new TestSetup {
        val pending = submitted.copy(reference = "pending-reference", status = status)
        when(repository.get(any(), any(), anyInt()))
          .thenReturn(Future.successful(Some(declaredReturn.copy(submissions = List(submitted, pending)))))

        await(service.process(item)) mustBe PendingSubmissions
        verifyNoInteractions(nps)
        verify(repository, never()).markNpsDeclared(any(), any(), anyInt())
      }
    }

    "must skip the NPS request and preserve the timestamp when already declared to NPS" in new TestSetup {
      when(repository.get(any(), any(), anyInt()))
        .thenReturn(Future.successful(Some(declaredReturn.copy(npsDeclaredOn = Some(testCreatedOn)))))

      await(service.process(item)) mustBe Declared
      verifyNoInteractions(nps)
      verify(repository, never()).markNpsDeclared(any(), any(), anyInt())
    }

    Seq("missing" -> None, "locally undeclared" -> Some(monthlyReturn)).foreach { (description, record) =>
      s"must fail without declaring a $description return" in new TestSetup {
        when(repository.get(any(), any(), anyInt())).thenReturn(Future.successful(record))

        await(service.process(item).failed) mustBe a[IllegalStateException]
        verifyNoInteractions(nps)
        verify(repository, never()).markNpsDeclared(any(), any(), anyInt())
      }
    }

    "must propagate a repository lookup failure without calling NPS" in new TestSetup {
      when(repository.get(any(), any(), anyInt())).thenReturn(Future.failed(updateFailure))

      await(service.process(item).failed) mustBe updateFailure
      verifyNoInteractions(nps)
    }

    Seq(BAD_REQUEST, TOO_MANY_REQUESTS, INTERNAL_SERVER_ERROR, SERVICE_UNAVAILABLE).foreach { status =>
      s"must not record a timestamp when NPS fails with [$status]" in new TestSetup {
        val failure = UpstreamErrorResponse("NPS declaration failed", status)
        when(nps.sendDeclaration(any())).thenReturn(Future.failed(failure))

        withCaptureOfLoggingFrom(Logger(service.getClass)) { events =>
          await(service.process(item).failed) mustBe failure
          events.exists(_.getLevel == WARN) mustBe false
          events.exists(_.getMessage.contains(successMessage)) mustBe false
        }
        verify(repository, never()).markNpsDeclared(any(), any(), anyInt())
      }
    }

    Seq("does not match" -> Future.successful(false), "fails" -> Future.failed(updateFailure)).foreach {
      (description, update) =>
        s"must fail and warn without claiming success when timestamp persistence $description" in new TestSetup {
          when(repository.markNpsDeclared(any(), any(), anyInt())).thenReturn(update)

          withCaptureOfLoggingFrom(Logger(service.getClass)) { events =>
            await(service.process(item).failed) mustBe a[RuntimeException]
            events.find(_.getLevel == WARN).value.getMessage must include(
              "NPS accepted declaration but could not record npsDeclaredOn"
            )
            events.exists(_.getMessage.contains(successMessage)) mustBe false
          }
        }
    }
  }

  "MonthlyReturnDeclarationProcessingService NPSDeclarationSubmitted audit" - {
    "must audit a success when NPS accepts the declaration" in new TestSetup {
      await(service.process(item)) mustBe Declared

      verify(audit).auditNpsDeclarationSubmitted(eqTo(declaredReturn), eqTo(None))(any())
    }

    "must audit a success even when npsDeclaredOn then fails to save" in new TestSetup {
      when(repository.markNpsDeclared(any(), any(), anyInt())).thenReturn(Future.failed(updateFailure))

      await(service.process(item).failed) mustBe updateFailure
      verify(audit).auditNpsDeclarationSubmitted(eqTo(declaredReturn), eqTo(None))(any())
    }

    "must audit a failure with the upstream message when NPS rejects the declaration" in new TestSetup {
      val failure = UpstreamErrorResponse("NPS declaration returned status [500]", INTERNAL_SERVER_ERROR)
      when(nps.sendDeclaration(any())).thenReturn(Future.failed(failure))

      await(service.process(item).failed) mustBe failure
      verify(audit).auditNpsDeclarationSubmitted(
        eqTo(declaredReturn),
        eqTo(Some("NPS declaration returned status [500]"))
      )(any())
    }

    "must audit a failure with the exception type when the NPS request fails without a response" in new TestSetup {
      val failure = new RuntimeException("connection refused")
      when(nps.sendDeclaration(any())).thenReturn(Future.failed(failure))

      await(service.process(item).failed) mustBe failure
      verify(audit).auditNpsDeclarationSubmitted(
        eqTo(declaredReturn),
        eqTo(Some("NPS declaration request failed [RuntimeException]"))
      )(any())
    }

    "must still declare and warn when the audit fails" in new TestSetup {
      when(audit.auditNpsDeclarationSubmitted(any(), any())(any()))
        .thenReturn(Future.failed(new RuntimeException("audit unavailable")))

      withCaptureOfLoggingFrom(Logger(service.getClass)) { events =>
        await(service.process(item)) mustBe Declared
        verify(repository).markNpsDeclared(testZReference, testTaxYear, testMonth)
        eventually {
          events.exists(event =>
            event.getLevel == WARN && event.getMessage.contains("Failed to audit NPSDeclarationSubmitted")
          ) mustBe true
        }
      }
    }

    "must not audit when the declaration is skipped" in new TestSetup {
      when(repository.get(any(), any(), anyInt()))
        .thenReturn(Future.successful(Some(declaredReturn.copy(npsDeclaredOn = Some(testCreatedOn)))))

      await(service.process(item)) mustBe Declared
      verifyNoInteractions(audit)
    }

    "must not audit while submissions are still pending" in new TestSetup {
      val pending = submitted.copy(reference = "pending-reference", status = MonthlyReturnSubmissionStatus.Stored)
      when(repository.get(any(), any(), anyInt()))
        .thenReturn(Future.successful(Some(declaredReturn.copy(submissions = List(submitted, pending)))))

      await(service.process(item)) mustBe PendingSubmissions
      verifyNoInteractions(audit)
    }
  }

  private trait TestSetup {
    val repository = mock[MonthlyReturnRepository]
    val nps        = mock[NpsConnector]
    val audit      = mock[AuditService]
    when(repository.get(any(), any(), anyInt())).thenReturn(Future.successful(Some(declaredReturn)))
    when(repository.markNpsDeclared(any(), any(), anyInt())).thenReturn(Future.successful(true))
    when(nps.sendDeclaration(any())).thenReturn(Future.unit)
    when(audit.auditNpsDeclarationSubmitted(any(), any())(any())).thenReturn(Future.unit)
    val service    = new MonthlyReturnDeclarationProcessingService(repository, nps, audit)
  }
}
