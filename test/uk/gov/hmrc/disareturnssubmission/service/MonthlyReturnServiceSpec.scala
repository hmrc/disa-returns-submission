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
import ch.qos.logback.classic.Level.*
import com.mongodb.MongoException
import org.apache.pekko.actor.ActorSystem
import org.mockito.ArgumentMatchers.{eq as eqTo, *}
import org.mockito.Mockito.{timeout as mockitoTimeout, *}
import play.api.Logger
import play.api.test.Helpers.*
import uk.gov.hmrc.disareturnssubmission.actions.MonthlyReturnSubmissionStoreAction
import uk.gov.hmrc.disareturnssubmission.config.AppConfig
import uk.gov.hmrc.disareturnssubmission.models.monthly.*
import uk.gov.hmrc.disareturnssubmission.repositories.*
import uk.gov.hmrc.disareturnssubmission.services.*
import uk.gov.hmrc.disareturnssubmission.utils.UuidGenerator
import uk.gov.hmrc.play.bootstrap.tools.LogCapturing

import java.time.Instant
import java.util.UUID
import scala.concurrent.*
class MonthlyReturnServiceSpec extends SpecBase with LogCapturing {

  private val zReference        = testZReference
  private val taxYear           = testTaxYear
  private val month             = testMonth
  private val submissionContext =
    s"submission [$testUploadReference] for zReference [$zReference], taxYear [$taxYear], month [$month]"

  private val generatedSubmissionId = UUID.randomUUID()

  private val monthlyReturn = MonthlyReturn(
    zReference = zReference,
    submissionId = testSubmissionId,
    taxYear = taxYear,
    month = month,
    createdOn = testExistingUpdatedOn,
    nilReturn = false,
    submissions = Nil,
    lastUpdated = testCreatedOn
  )

  "MonthlyReturnService" - {

    "create" - {

      "must evaluate the reporting window at its already-resolved instant" in new TestSetup {
        val now                    = Instant.parse("2026-06-17T12:00:00Z")
        val reportingWindowService = mock[MonthlyReturnReportingWindowService]
        val resolvedService        = new MonthlyReturnService(
          monthlyReturnRepository = mockMonthlyReturnRepository,
          submissionStoreAction = mockSubStoreAction,
          reportingWindowService = reportingWindowService,
          uuidGenerator = mockUuidGenerator,
          workItemRepository = mockWorkItemRepository,
          appConfig = appConfig,
          actorSystem = inject[ActorSystem]
        )
        when(reportingWindowService.resolve(zReference))
          .thenReturn(
            Future.successful(ResolvedReportingWindow(now, windowStart = now, windowEnd = now, isOpen = false))
          )

        await(resolvedService.create(zReference, taxYear, month, nilReturn = false)) mustBe
          CreateMonthlyReturnResult.OutsideDeclarationPeriod

        verify(reportingWindowService).resolve(zReference)
      }

      "must return Created when the repository creates the MonthlyReturn" in new TestSetup {
        when(mockUuidGenerator.randomUuid()).thenReturn(testSubmissionId)
        when(
          mockMonthlyReturnRepository.create(
            eqTo(zReference),
            eqTo(taxYear),
            eqTo(month),
            eqTo(testSubmissionId),
            eqTo(true)
          )
        )
          .thenReturn(Future.successful(Some(monthlyReturn)))

        await(
          service
            .create(zReference, taxYear, month, nilReturn = true)
        ) mustBe CreateMonthlyReturnResult.Created(testSubmissionId)
      }

      "must return AlreadyExists with the existing submissionId when the repository rejects the create" in new TestSetup {
        when(mockUuidGenerator.randomUuid()).thenReturn(generatedSubmissionId)
        when(
          mockMonthlyReturnRepository.create(
            eqTo(zReference),
            eqTo(taxYear),
            eqTo(month),
            eqTo(generatedSubmissionId),
            eqTo(false)
          )
        )
          .thenReturn(Future.successful(None))
        when(mockMonthlyReturnRepository.get(eqTo(zReference), eqTo(taxYear), eqTo(month)))
          .thenReturn(Future.successful(Some(monthlyReturn)))

        await(
          service
            .create(zReference, taxYear, month, nilReturn = false)
        ) mustBe CreateMonthlyReturnResult.AlreadyExists(testSubmissionId)
      }

      "must fail when the repository rejects the create but the MonthlyReturn cannot be found" in new TestSetup {
        when(mockUuidGenerator.randomUuid()).thenReturn(generatedSubmissionId)
        when(
          mockMonthlyReturnRepository.create(
            eqTo(zReference),
            eqTo(taxYear),
            eqTo(month),
            eqTo(generatedSubmissionId),
            eqTo(false)
          )
        )
          .thenReturn(Future.successful(None))
        when(mockMonthlyReturnRepository.get(eqTo(zReference), eqTo(taxYear), eqTo(month)))
          .thenReturn(Future.successful(None))

        await(
          service
            .create(zReference, taxYear, month, nilReturn = false)
            .failed
        ) mustBe a[IllegalStateException]
      }

      "must fail when the repository fails to create the MonthlyReturn" in new TestSetup {
        when(mockUuidGenerator.randomUuid()).thenReturn(generatedSubmissionId)
        when(
          mockMonthlyReturnRepository.create(
            eqTo(zReference),
            eqTo(taxYear),
            eqTo(month),
            eqTo(generatedSubmissionId),
            eqTo(false)
          )
        )
          .thenReturn(Future.failed(new RuntimeException(testMongoDownMessage)))

        await(
          service
            .create(zReference, taxYear, month, nilReturn = false)
            .failed
        ) mustBe a[RuntimeException]
      }

      "must return OutsideDeclarationPeriod if creating a return on April 2026 for April 2026" in new TestSetup {
        val startDate = buildService(Instant.parse(s"2026-04-0${appConfig.declarationPeriodStart}T00:00:00Z"))

        await(
          startDate
            .create(zReference, "2025-26", 4, nilReturn = false)
        ) mustBe
          CreateMonthlyReturnResult.OutsideDeclarationPeriod

        verifyNoInteractions(mockMonthlyReturnRepository)
      }

      "must allow creating a return on April 2026 for March 2026 in the 2025-26 tax year" in new TestSetup {
        val startDate = buildService(Instant.parse(s"2026-04-0${appConfig.declarationPeriodStart}T00:00:00Z"))
        when(mockUuidGenerator.randomUuid()).thenReturn(testSubmissionId)
        when(
          mockMonthlyReturnRepository.create(
            eqTo(zReference),
            eqTo("2025-26"),
            eqTo(3),
            eqTo(testSubmissionId),
            eqTo(false)
          )
        ).thenReturn(Future.successful(Some(monthlyReturn.copy(taxYear = "2025-26", month = 3))))

        await(
          startDate
            .create(zReference, "2025-26", 3, nilReturn = false)
        ) mustBe CreateMonthlyReturnResult.Created(testSubmissionId)
      }

      "must return OutsideDeclarationPeriod if creating a return on April 2026 for the 2027-28 tax year" in new TestSetup {
        val startDate = buildService(Instant.parse(s"2026-04-0${appConfig.declarationPeriodStart}T00:00:00Z"))

        await(
          startDate
            .create(zReference, "2027-28", 4, nilReturn = false)
        ) mustBe
          CreateMonthlyReturnResult.OutsideDeclarationPeriod

        verifyNoInteractions(mockMonthlyReturnRepository)
      }

      "must return OutsideDeclarationPeriod if creating a return on July 2026 for August 2026" in new TestSetup {
        val startDate = buildService(Instant.parse(s"2026-07-0${appConfig.declarationPeriodStart}T00:00:00Z"))

        await(
          startDate
            .create(zReference, "2026-27", 8, nilReturn = false)
        ) mustBe
          CreateMonthlyReturnResult.OutsideDeclarationPeriod

        verifyNoInteractions(mockMonthlyReturnRepository)
      }

      "must allow creating a return on July 2026 for June 2026" in new TestSetup {
        val startDate = buildService(Instant.parse(s"2026-07-0${appConfig.declarationPeriodStart}T00:00:00Z"))
        when(mockUuidGenerator.randomUuid()).thenReturn(testSubmissionId)
        when(
          mockMonthlyReturnRepository.create(
            eqTo(zReference),
            eqTo("2026-27"),
            eqTo(6),
            eqTo(testSubmissionId),
            eqTo(false)
          )
        ).thenReturn(Future.successful(Some(monthlyReturn.copy(month = 6))))

        await(
          startDate
            .create(zReference, "2026-27", 6, nilReturn = false)
        ) mustBe CreateMonthlyReturnResult.Created(testSubmissionId)
      }
    }

    "get" - {

      "must return Some(monthlyReturn) when the repository finds a monthly return" in new TestSetup {
        when(mockMonthlyReturnRepository.get(eqTo(zReference), eqTo(taxYear), eqTo(month)))
          .thenReturn(Future.successful(Some(monthlyReturn)))

        await(service.get(zReference, taxYear, month)) mustBe Some(monthlyReturn)
      }

      "must return None when the repository does not find a monthly return" in new TestSetup {
        when(mockMonthlyReturnRepository.get(eqTo(zReference), eqTo(taxYear), eqTo(month)))
          .thenReturn(Future.successful(None))

        await(service.get(zReference, taxYear, month)) mustBe None
      }

      "must fail when the repository throws an exception" in new TestSetup {
        when(mockMonthlyReturnRepository.get(eqTo(zReference), eqTo(taxYear), eqTo(month)))
          .thenReturn(Future.failed(new RuntimeException(testMongoDownMessage)))

        await(service.get(zReference, taxYear, month).failed) mustBe a[RuntimeException]
      }
    }

    "declare with nilReturn false" - {

      "must return Declared when the repository declares the MonthlyReturn" in new TestSetup {
        when(mockMonthlyReturnRepository.declare(eqTo(zReference), eqTo(taxYear), eqTo(month), eqTo(List.empty)))
          .thenReturn(
            Future.successful(DeclareMonthlyReturnRepositoryResult.MonthlyReturnDeclared)
          )

        await(
          service
            .declare(zReference, taxYear, month, nilReturn = false)
        ) mustBe DeclareMonthlyReturnResult.Declared

        verify(mockMonthlyReturnRepository).declare(eqTo(zReference), eqTo(taxYear), eqTo(month), eqTo(List.empty))
      }

      "must pass pending submission IDs to the repository" in new TestSetup {
        val pendingSubmissionIds = List(testUploadReference, "second-reference")
        when(
          mockMonthlyReturnRepository.declare(
            eqTo(zReference),
            eqTo(taxYear),
            eqTo(month),
            eqTo(pendingSubmissionIds)
          )
        ).thenReturn(Future.successful(DeclareMonthlyReturnRepositoryResult.MonthlyReturnDeclared))

        await(
          service
            .declare(
              zReference,
              taxYear,
              month,
              nilReturn = false,
              pendingSubmissionIds = pendingSubmissionIds
            )
        ) mustBe DeclareMonthlyReturnResult.Declared
      }

      "must return AlreadyDeclared when the repository rejects a duplicate declaration" in new TestSetup {
        when(mockMonthlyReturnRepository.declare(eqTo(zReference), eqTo(taxYear), eqTo(month), eqTo(List.empty)))
          .thenReturn(Future.successful(DeclareMonthlyReturnRepositoryResult.MonthlyReturnAlreadyDeclared))

        await(
          service
            .declare(zReference, taxYear, month, nilReturn = false)
        ) mustBe DeclareMonthlyReturnResult.AlreadyDeclared
      }

      "must return MonthlyReturnNotFound when the repository cannot find the MonthlyReturn" in new TestSetup {
        when(mockMonthlyReturnRepository.declare(eqTo(zReference), eqTo(taxYear), eqTo(month), eqTo(List.empty)))
          .thenReturn(Future.successful(DeclareMonthlyReturnRepositoryResult.MonthlyReturnNotFound))

        await(
          service
            .declare(zReference, taxYear, month, nilReturn = false)
        ) mustBe DeclareMonthlyReturnResult.MonthlyReturnNotFound
      }

      "must allow declarations from the configured start day" in new TestSetup {
        val startOfDeclarationPeriod = Instant.parse("2026-06-06T00:00:00Z")
        val startDayService          = buildService(startOfDeclarationPeriod)
        when(mockMonthlyReturnRepository.declare(eqTo(zReference), eqTo(taxYear), eqTo(month), eqTo(List.empty)))
          .thenReturn(
            Future.successful(DeclareMonthlyReturnRepositoryResult.MonthlyReturnDeclared)
          )

        await(startDayService.declare(zReference, taxYear, month, nilReturn = false)) mustBe
          DeclareMonthlyReturnResult.Declared
      }

      "must return OutsideDeclarationPeriod and not call the repository before the configured start day" in new TestSetup {
        val beforeStartDayService = buildService(Instant.parse("2026-06-05T23:59:59Z"))

        await(beforeStartDayService.declare(zReference, taxYear, month, nilReturn = false)) mustBe
          DeclareMonthlyReturnResult.OutsideDeclarationPeriod

        verifyNoInteractions(mockMonthlyReturnRepository)
      }

      "must allow declarations until the end of the configured end day" in new TestSetup {
        val endOfDeclarationPeriod = Instant.parse("2026-06-19T23:59:59Z")
        val endDayService          = buildService(endOfDeclarationPeriod)
        when(mockMonthlyReturnRepository.declare(eqTo(zReference), eqTo(taxYear), eqTo(month), eqTo(List.empty)))
          .thenReturn(
            Future.successful(DeclareMonthlyReturnRepositoryResult.MonthlyReturnDeclared)
          )

        await(endDayService.declare(zReference, taxYear, month, nilReturn = false)) mustBe
          DeclareMonthlyReturnResult.Declared
      }

      "must return OutsideDeclarationPeriod and not call the repository after the configured end day" in new TestSetup {
        val afterEndDayService = buildService(Instant.parse("2026-06-20T00:00:00Z"))

        await(afterEndDayService.declare(zReference, taxYear, month, nilReturn = false)) mustBe
          DeclareMonthlyReturnResult.OutsideDeclarationPeriod

        verifyNoInteractions(mockMonthlyReturnRepository)
      }

      "must return OutsideDeclarationPeriod if declaring on April 2026 for April 2026" in new TestSetup {
        val startDate = buildService(Instant.parse(s"2026-04-0${appConfig.declarationPeriodStart}T00:00:00Z"))

        await(startDate.declare(zReference, "2025-26", 4, nilReturn = false)) mustBe
          DeclareMonthlyReturnResult.OutsideDeclarationPeriod

        verifyNoInteractions(mockMonthlyReturnRepository)
      }

      "must allow declaring on April 2026 for March 2026 in the 2025-26 tax year" in new TestSetup {
        val startDate = buildService(Instant.parse(s"2026-04-0${appConfig.declarationPeriodStart}T00:00:00Z"))
        when(mockMonthlyReturnRepository.declare(eqTo(zReference), eqTo("2025-26"), eqTo(3), eqTo(List.empty)))
          .thenReturn(Future.successful(DeclareMonthlyReturnRepositoryResult.MonthlyReturnDeclared))

        await(startDate.declare(zReference, "2025-26", 3, nilReturn = false)) mustBe
          DeclareMonthlyReturnResult.Declared
      }

      "must return OutsideDeclarationPeriod if declaring on April 2026 for the 2027-28 tax year" in new TestSetup {
        val startDate = buildService(Instant.parse(s"2026-04-0${appConfig.declarationPeriodStart}T00:00:00Z"))

        await(startDate.declare(zReference, "2027-28", 4, nilReturn = false)) mustBe
          DeclareMonthlyReturnResult.OutsideDeclarationPeriod

        verifyNoInteractions(mockMonthlyReturnRepository)
      }

      "must return OutsideDeclarationPeriod if declaring on July 2026 for August 2026" in new TestSetup {
        val startDate = buildService(Instant.parse(s"2026-07-0${appConfig.declarationPeriodStart}T00:00:00Z"))

        await(
          startDate
            .declare(zReference, "2026-27", 8, nilReturn = false)
        ) mustBe
          DeclareMonthlyReturnResult.OutsideDeclarationPeriod

        verifyNoInteractions(mockMonthlyReturnRepository)
      }

      "must allow declaring on July 2026 for June 2026" in new TestSetup {
        val startDate = buildService(Instant.parse(s"2026-07-0${appConfig.declarationPeriodStart}T00:00:00Z"))
        when(mockMonthlyReturnRepository.declare(eqTo(zReference), eqTo("2026-27"), eqTo(6), eqTo(List.empty)))
          .thenReturn(Future.successful(DeclareMonthlyReturnRepositoryResult.MonthlyReturnDeclared))

        await(
          startDate
            .declare(zReference, "2026-27", 6, nilReturn = false)
        ) mustBe DeclareMonthlyReturnResult.Declared
      }
    }

    "submitReturn" - {
      "must return UpdateSuccessful for a missing submission before declaration" in new TestSetup {

        when(mockMonthlyReturnRepository.get(any(), any(), any()))
          .thenReturn(Future.successful(Some(monthlyReturn)))

        when(mockSubStoreAction.store(any(), any(), eqTo(testUploadReference)))
          .thenReturn(Future.successful(SubmitReturnResult.UpdateSuccessful))

        await(
          service
            .storeSubmission(
              zReference,
              taxYear,
              month,
              testUploadReference,
              testTempFile
            )
        ) mustBe SubmitReturnResult.UpdateSuccessful

        verify(mockWorkItemRepository).enqueue(
          MonthlyReturnSubmissionWorkItem(zReference, taxYear, month, testUploadReference)
        )
      }

      "must allow an existing CREATED submission after declaration" in new TestSetup {
        val declaredReturn = monthlyReturn.copy(
          submissions = List(
            MonthlyReturnSubmission(
              reference = testUploadReference,
              status = MonthlyReturnSubmissionStatus.Created,
              createdOn = testCreatedOn
            )
          ),
          declaredOn = Some(testCreatedOn)
        )
        when(mockMonthlyReturnRepository.get(any(), any(), any()))
          .thenReturn(Future.successful(Some(declaredReturn)))
        when(mockSubStoreAction.store(any(), eqTo(declaredReturn), eqTo(testUploadReference)))
          .thenReturn(Future.successful(SubmitReturnResult.UpdateSuccessful))

        await(
          service
            .storeSubmission(zReference, taxYear, month, testUploadReference, testTempFile)
        ) mustBe SubmitReturnResult.UpdateSuccessful
      }

      "must return SubmissionConflict without storing when the submission is already STORED" in new TestSetup {
        val monthlyReturnWithStoredSubmission = monthlyReturn.copy(
          submissions = List(
            MonthlyReturnSubmission(
              reference = testUploadReference,
              status = MonthlyReturnSubmissionStatus.Stored,
              createdOn = testCreatedOn,
              submissionDetails = Some(fileUploadDetails)
            )
          )
        )
        when(mockMonthlyReturnRepository.get(any(), any(), any()))
          .thenReturn(Future.successful(Some(monthlyReturnWithStoredSubmission)))

        withCaptureOfLoggingFrom(Logger(service.getClass)) { events =>
          await(service.storeSubmission(zReference, taxYear, month, testUploadReference, testTempFile)) mustBe
            SubmitReturnResult.SubmissionConflict
          events.last.getLevel mustBe INFO
          events.last.getMessage must include(s"Confirmed NPS transfer work item for $submissionContext")
        }

        verifyNoInteractions(mockSubStoreAction)
        verify(mockWorkItemRepository).enqueue(
          MonthlyReturnSubmissionWorkItem(zReference, taxYear, month, testUploadReference)
        )
      }

      "must not upload or enqueue a submission already sent to NPS" in new TestSetup {
        val submitted = monthlyReturn.copy(submissions =
          List(
            MonthlyReturnSubmission(
              testUploadReference,
              MonthlyReturnSubmissionStatus.NpsSubmitted,
              testCreatedOn,
              Some(fileUploadDetails)
            )
          )
        )
        when(mockMonthlyReturnRepository.get(any(), any(), any())).thenReturn(Future.successful(Some(submitted)))

        withCaptureOfLoggingFrom(Logger(service.getClass)) { events =>
          await(service.storeSubmission(zReference, taxYear, month, testUploadReference, testTempFile)) mustBe
            SubmitReturnResult.SubmissionConflict
          events.last.getLevel mustBe INFO
          events.last.getMessage must include(s"already submitted to NPS; skipping enqueue for $submissionContext")
          events.exists(_.getMessage.contains("Confirmed NPS transfer work item")) mustBe false
        }

        verifyNoInteractions(mockSubStoreAction, mockWorkItemRepository)
      }

      "must await enqueue confirmation after storing" in new TestSetup {
        val enqueued = Promise[Unit]()
        when(mockMonthlyReturnRepository.get(any(), any(), any())).thenReturn(Future.successful(Some(monthlyReturn)))
        when(mockSubStoreAction.store(any(), any(), any()))
          .thenReturn(Future.successful(SubmitReturnResult.UpdateSuccessful))
        when(mockWorkItemRepository.enqueue(any())).thenReturn(enqueued.future)

        withCaptureOfLoggingFrom(Logger(service.getClass)) { events =>
          val result = service.storeSubmission(zReference, taxYear, month, testUploadReference, testTempFile)
          verify(mockWorkItemRepository, mockitoTimeout(1000)).enqueue(any())
          result.isCompleted mustBe false
          events.exists(_.getMessage.contains("Confirmed NPS transfer work item")) mustBe false
          enqueued.success(())

          await(result) mustBe SubmitReturnResult.UpdateSuccessful
          events.last.getLevel mustBe INFO
          events.last.getMessage must include(s"Confirmed NPS transfer work item for $submissionContext")
        }
      }

      "must retry Mongo enqueue failures up to the configured attempt limit" in new TestSetup {
        when(mockMonthlyReturnRepository.get(any(), any(), any())).thenReturn(Future.successful(Some(monthlyReturn)))
        when(mockSubStoreAction.store(any(), any(), any()))
          .thenReturn(Future.successful(SubmitReturnResult.UpdateSuccessful))
        when(mockWorkItemRepository.enqueue(any())).thenReturn(Future.failed(new MongoException("mongo unavailable")))

        withCaptureOfLoggingFrom(Logger(service.getClass)) { events =>
          await(service.storeSubmission(zReference, taxYear, month, testUploadReference, testTempFile).failed) mustBe
            a[MongoException]
          val retries = events.filter(_.getLevel == WARN)
          retries.size mustBe appConfig.monthlyReturnSubmissionEnqueueAttempts - 1
          retries.map(_.getMessage) mustBe (appConfig.monthlyReturnSubmissionEnqueueAttempts - 1 to 1 by -1).map {
            remaining =>
              s"[MonthlyReturnService][retryMongo] Retrying NPS enqueue for $submissionContext in [1 second]; attempts remaining [$remaining], error [MongoException]"
          }.toList
          events.last.getLevel mustBe ERROR
          events.last.getMessage must include(s"Failed to store or enqueue $submissionContext")
          events.exists(_.getMessage.contains("Confirmed NPS transfer work item")) mustBe false
        }
        verify(mockWorkItemRepository, times(appConfig.monthlyReturnSubmissionEnqueueAttempts)).enqueue(any())
      }

      "must recover after a transient enqueue failure without repeating storage" in new TestSetup {
        when(mockMonthlyReturnRepository.get(any(), any(), any())).thenReturn(Future.successful(Some(monthlyReturn)))
        when(mockSubStoreAction.store(any(), any(), any()))
          .thenReturn(Future.successful(SubmitReturnResult.UpdateSuccessful))
        when(mockWorkItemRepository.enqueue(any()))
          .thenReturn(Future.failed(new MongoException("mongo unavailable")), Future.unit)

        withCaptureOfLoggingFrom(Logger(service.getClass)) { events =>
          await(service.storeSubmission(zReference, taxYear, month, testUploadReference, testTempFile)) mustBe
            SubmitReturnResult.UpdateSuccessful
          events.count(_.getLevel == WARN) mustBe 1
          events.find(_.getLevel == WARN).value.getMessage must include(s"Retrying NPS enqueue for $submissionContext")
          events.last.getLevel mustBe INFO
          events.last.getMessage                           must include(s"Confirmed NPS transfer work item for $submissionContext")
        }
        verify(mockWorkItemRepository, times(2)).enqueue(any())
        verify(mockSubStoreAction).store(any(), any(), any())
      }

      "must log a non-Mongo enqueue failure without retrying or claiming confirmation" in new TestSetup {
        val failure = new IllegalStateException("enqueue unavailable")
        when(mockMonthlyReturnRepository.get(any(), any(), any())).thenReturn(Future.successful(Some(monthlyReturn)))
        when(mockSubStoreAction.store(any(), any(), any()))
          .thenReturn(Future.successful(SubmitReturnResult.UpdateSuccessful))
        when(mockWorkItemRepository.enqueue(any())).thenReturn(Future.failed(failure))

        withCaptureOfLoggingFrom(Logger(service.getClass)) { events =>
          await(
            service.storeSubmission(zReference, taxYear, month, testUploadReference, testTempFile).failed
          ) mustBe failure
          events.last.getLevel mustBe ERROR
          events.last.getMessage must include(s"Failed to store or enqueue $submissionContext")
          events.exists(_.getMessage.contains("Retrying NPS enqueue")) mustBe false
          events.exists(_.getMessage.contains("Confirmed NPS transfer work item")) mustBe false
        }
        verify(mockWorkItemRepository).enqueue(any())
        verify(mockSubStoreAction).store(any(), any(), any())
      }

      "must recover enqueueing when a concurrent request stored the submission" in new TestSetup {
        val stored = monthlyReturn.copy(submissions =
          List(
            MonthlyReturnSubmission(
              testUploadReference,
              MonthlyReturnSubmissionStatus.Stored,
              testCreatedOn,
              Some(fileUploadDetails)
            )
          )
        )
        when(mockMonthlyReturnRepository.get(any(), any(), any()))
          .thenReturn(Future.successful(Some(monthlyReturn)), Future.successful(Some(stored)))
        when(mockSubStoreAction.store(any(), any(), any()))
          .thenReturn(Future.successful(SubmitReturnResult.SubmissionConflict))

        await(service.storeSubmission(zReference, taxYear, month, testUploadReference, testTempFile)) mustBe
          SubmitReturnResult.SubmissionConflict
        verify(mockWorkItemRepository).enqueue(
          MonthlyReturnSubmissionWorkItem(zReference, taxYear, month, testUploadReference)
        )
      }

      "must not enqueue if a concurrent request already sent the submission to NPS" in new TestSetup {
        val submitted = monthlyReturn.copy(submissions =
          List(
            MonthlyReturnSubmission(
              testUploadReference,
              MonthlyReturnSubmissionStatus.NpsSubmitted,
              testCreatedOn,
              Some(fileUploadDetails)
            )
          )
        )
        when(mockMonthlyReturnRepository.get(any(), any(), any()))
          .thenReturn(Future.successful(Some(monthlyReturn)), Future.successful(Some(submitted)))
        when(mockSubStoreAction.store(any(), any(), any()))
          .thenReturn(Future.successful(SubmitReturnResult.SubmissionConflict))

        await(service.storeSubmission(zReference, taxYear, month, testUploadReference, testTempFile)) mustBe
          SubmitReturnResult.SubmissionConflict
        verifyNoInteractions(mockWorkItemRepository)
      }

      "must return SubmissionConflict for a missing submission after declaration" in new TestSetup {
        when(mockMonthlyReturnRepository.get(any(), any(), any()))
          .thenReturn(Future.successful(Some(monthlyReturn.copy(declaredOn = Some(testCreatedOn)))))

        await(
          service
            .storeSubmission(zReference, taxYear, month, testUploadReference, testTempFile)
        ) mustBe SubmitReturnResult.SubmissionConflict

        verifyNoInteractions(mockSubStoreAction)
      }

      "must return SubmissionConflict if the state changes while storing" in new TestSetup {
        when(mockMonthlyReturnRepository.get(any(), any(), any()))
          .thenReturn(Future.successful(Some(monthlyReturn)))
        when(mockSubStoreAction.store(any(), any(), eqTo(testUploadReference)))
          .thenReturn(Future.successful(SubmitReturnResult.SubmissionConflict))

        await(
          service
            .storeSubmission(zReference, taxYear, month, testUploadReference, testTempFile)
        ) mustBe SubmitReturnResult.SubmissionConflict
      }

      "must return MonthlyReturnNotFound if a Monthly return hasn't been created yet" in new TestSetup {

        when(mockMonthlyReturnRepository.get(any(), any(), any()))
          .thenReturn(Future.successful(None))

        await(
          service
            .storeSubmission(
              zReference,
              taxYear,
              month,
              testUploadReference,
              testTempFile
            )
        ) mustBe SubmitReturnResult.MonthlyReturnNotFound

        verifyNoInteractions(mockSubStoreAction)

      }

      "must fail when the submission store action fails" in new TestSetup {
        when(mockMonthlyReturnRepository.get(any(), any(), any()))
          .thenReturn(Future.successful(Some(monthlyReturn)))

        when(mockSubStoreAction.store(any(), any(), eqTo(testUploadReference)))
          .thenReturn(Future.failed(new RuntimeException(testMongoDownMessage)))

        await(
          service
            .storeSubmission(
              zReference,
              taxYear,
              month,
              testUploadReference,
              testTempFile
            )
            .failed
        ) mustBe a[RuntimeException]
        verifyNoInteractions(mockWorkItemRepository)
      }
    }

    "declare with nilReturn true" - {

      "must reject pending submission IDs without calling the repository" in new TestSetup {
        await(
          service
            .declare(
              zReference,
              taxYear,
              month,
              nilReturn = true,
              pendingSubmissionIds = List(testUploadReference)
            )
        ) mustBe DeclareMonthlyReturnResult.PendingSubmissionsForNilReturn

        verifyNoInteractions(mockMonthlyReturnRepository)
      }

      "must return Declared when no monthly return exists and one is created" in new TestSetup {
        when(mockMonthlyReturnRepository.declareNilReturn(eqTo(zReference), eqTo(taxYear), eqTo(month)))
          .thenReturn(Future.successful(DeclareMonthlyReturnRepositoryResult.MonthlyReturnNotFound))
        when(mockUuidGenerator.randomUuid()).thenReturn(testSubmissionId)
        when(
          mockMonthlyReturnRepository.create(
            eqTo(zReference),
            eqTo(taxYear),
            eqTo(month),
            eqTo(testSubmissionId),
            eqTo(true)
          )
        )
          .thenReturn(Future.successful(Some(monthlyReturn.copy(nilReturn = true))))
        when(mockMonthlyReturnRepository.declare(eqTo(zReference), eqTo(taxYear), eqTo(month), eqTo(List.empty)))
          .thenReturn(Future.successful(DeclareMonthlyReturnRepositoryResult.MonthlyReturnDeclared))

        await(
          service
            .declare(zReference, taxYear, month, nilReturn = true)
        ) mustBe DeclareMonthlyReturnResult.Declared

        verify(mockUuidGenerator).randomUuid()
        verify(mockMonthlyReturnRepository).declare(eqTo(zReference), eqTo(taxYear), eqTo(month), eqTo(List.empty))
      }

      "must return AlreadyDeclared when created nil return is already declared before fallback declaration completes" in new TestSetup {
        when(mockMonthlyReturnRepository.declareNilReturn(eqTo(zReference), eqTo(taxYear), eqTo(month)))
          .thenReturn(Future.successful(DeclareMonthlyReturnRepositoryResult.MonthlyReturnNotFound))
        when(mockUuidGenerator.randomUuid()).thenReturn(testSubmissionId)
        when(
          mockMonthlyReturnRepository.create(
            eqTo(zReference),
            eqTo(taxYear),
            eqTo(month),
            eqTo(testSubmissionId),
            eqTo(true)
          )
        )
          .thenReturn(Future.successful(Some(monthlyReturn.copy(nilReturn = true))))
        when(mockMonthlyReturnRepository.declare(eqTo(zReference), eqTo(taxYear), eqTo(month), eqTo(List.empty)))
          .thenReturn(Future.successful(DeclareMonthlyReturnRepositoryResult.MonthlyReturnAlreadyDeclared))

        await(
          service
            .declare(zReference, taxYear, month, nilReturn = true)
        ) mustBe DeclareMonthlyReturnResult.AlreadyDeclared
      }

      "must return MonthlyReturnNotFound when created nil return cannot be found by fallback declaration" in new TestSetup {
        when(mockMonthlyReturnRepository.declareNilReturn(eqTo(zReference), eqTo(taxYear), eqTo(month)))
          .thenReturn(Future.successful(DeclareMonthlyReturnRepositoryResult.MonthlyReturnNotFound))
        when(mockUuidGenerator.randomUuid()).thenReturn(testSubmissionId)
        when(
          mockMonthlyReturnRepository.create(
            eqTo(zReference),
            eqTo(taxYear),
            eqTo(month),
            eqTo(testSubmissionId),
            eqTo(true)
          )
        )
          .thenReturn(Future.successful(Some(monthlyReturn.copy(nilReturn = true))))
        when(mockMonthlyReturnRepository.declare(eqTo(zReference), eqTo(taxYear), eqTo(month), eqTo(List.empty)))
          .thenReturn(Future.successful(DeclareMonthlyReturnRepositoryResult.MonthlyReturnNotFound))

        await(
          service
            .declare(zReference, taxYear, month, nilReturn = true)
        ) mustBe DeclareMonthlyReturnResult.MonthlyReturnNotFound
      }

      "must return Declared and update existing monthly return to nil return" in new TestSetup {
        when(mockMonthlyReturnRepository.declareNilReturn(eqTo(zReference), eqTo(taxYear), eqTo(month)))
          .thenReturn(Future.successful(DeclareMonthlyReturnRepositoryResult.MonthlyReturnDeclared))

        await(
          service
            .declare(zReference, taxYear, month, nilReturn = true)
        ) mustBe DeclareMonthlyReturnResult.Declared

        verify(mockMonthlyReturnRepository).declareNilReturn(eqTo(zReference), eqTo(taxYear), eqTo(month))
        verify(mockUuidGenerator, never()).randomUuid()
        verify(mockMonthlyReturnRepository, never()).create(any(), any(), any(), any(), any())
        verify(mockMonthlyReturnRepository, never()).declare(any(), any(), any(), any())
      }

      "must return AlreadyDeclared when existing monthly return is already declared" in new TestSetup {
        when(mockMonthlyReturnRepository.declareNilReturn(eqTo(zReference), eqTo(taxYear), eqTo(month)))
          .thenReturn(Future.successful(DeclareMonthlyReturnRepositoryResult.MonthlyReturnAlreadyDeclared))

        await(
          service
            .declare(zReference, taxYear, month, nilReturn = true)
        ) mustBe DeclareMonthlyReturnResult.AlreadyDeclared

        verify(mockUuidGenerator, never()).randomUuid()
        verify(mockMonthlyReturnRepository, never()).create(any(), any(), any(), any(), any())
        verify(mockMonthlyReturnRepository, never()).declare(any(), any(), any(), any())
      }

      "must return OutsideDeclarationPeriod when outside declaration period" in new TestSetup {
        val beforeStartDayService = buildService(Instant.parse("2026-06-05T23:59:59Z"))

        await(beforeStartDayService.declare(zReference, taxYear, month, nilReturn = true)) mustBe
          DeclareMonthlyReturnResult.OutsideDeclarationPeriod

        verifyNoInteractions(mockMonthlyReturnRepository)
      }

      "must fail when no monthly return exists and create returns None" in new TestSetup {
        when(mockMonthlyReturnRepository.declareNilReturn(eqTo(zReference), eqTo(taxYear), eqTo(month)))
          .thenReturn(Future.successful(DeclareMonthlyReturnRepositoryResult.MonthlyReturnNotFound))
        when(mockUuidGenerator.randomUuid()).thenReturn(testSubmissionId)
        when(
          mockMonthlyReturnRepository.create(
            eqTo(zReference),
            eqTo(taxYear),
            eqTo(month),
            eqTo(testSubmissionId),
            eqTo(true)
          )
        )
          .thenReturn(Future.successful(None))

        await(service.declare(zReference, taxYear, month, nilReturn = true).failed) mustBe a[RuntimeException]
      }

      "must fail when repository throws an exception" in new TestSetup {
        when(mockMonthlyReturnRepository.declareNilReturn(eqTo(zReference), eqTo(taxYear), eqTo(month)))
          .thenReturn(Future.failed(new RuntimeException("mongodb down")))

        await(service.declare(zReference, taxYear, month, nilReturn = true).failed) mustBe a[RuntimeException]
      }

    }
  }

  private trait TestSetup {
    val mockMonthlyReturnRepository = mock[MonthlyReturnRepository]
    val mockSubStoreAction          = mock[MonthlyReturnSubmissionStoreAction]
    val appConfig                   = inject[AppConfig]
    val mockUuidGenerator           = mock[UuidGenerator]
    val mockWorkItemRepository      = mock[MonthlyReturnSubmissionWorkItemRepository]
    val service                     = buildService(Instant.parse("2026-06-17T12:00:00Z"))

    when(mockWorkItemRepository.enqueue(any[MonthlyReturnSubmissionWorkItem])).thenReturn(Future.unit)

    def buildService(now: Instant): MonthlyReturnService = {
      val timeSource: TimeSource = (_: String) => Future.successful(now)
      new MonthlyReturnService(
        monthlyReturnRepository = mockMonthlyReturnRepository,
        mockSubStoreAction,
        reportingWindowService = new MonthlyReturnReportingWindowService(appConfig, timeSource),
        uuidGenerator = mockUuidGenerator,
        workItemRepository = mockWorkItemRepository,
        appConfig = appConfig,
        actorSystem = inject[ActorSystem]
      )
    }
  }
}
