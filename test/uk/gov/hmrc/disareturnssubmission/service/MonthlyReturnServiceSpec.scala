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
import org.mockito.ArgumentMatchers.{any, eq as eqTo}
import org.mockito.Mockito.{never, reset, verify, verifyNoInteractions, when}
import org.scalatest.BeforeAndAfterEach
import uk.gov.hmrc.disareturnssubmission.actions.SubmissionStoreAction
import uk.gov.hmrc.disareturnssubmission.config.AppConfig
import uk.gov.hmrc.disareturnssubmission.models.*
import uk.gov.hmrc.disareturnssubmission.repositories.{DeclareMonthlyReturnRepositoryResult, MonthlyReturnRepository}
import uk.gov.hmrc.disareturnssubmission.services.{CreateMonthlyReturnResult, DeclareMonthlyReturnResult, MonthlyReturnService, ReportingWindowService, ResolvedReportingWindow, SubmitReturnResult, TimeSource}
import uk.gov.hmrc.disareturnssubmission.utils.UuidGenerator

import java.time.Instant
import java.util.UUID
import scala.concurrent.Future
class MonthlyReturnServiceSpec extends SpecBase with BeforeAndAfterEach {

  private val mockMonthlyReturnRepository = mock[MonthlyReturnRepository]
  private val mockSubStoreAction          = mock[SubmissionStoreAction]
  private val appConfig                   = inject[AppConfig]
  private val mockUuidGenerator           = mock[UuidGenerator]
  private val service                     = buildService(Instant.parse("2026-06-17T12:00:00Z"))

  private val zReference = testZReference
  private val taxYear    = testTaxYear
  private val month      = testMonth

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

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    reset(mockMonthlyReturnRepository)
    reset(mockSubStoreAction)
    reset(mockUuidGenerator)
  }

  "MonthlyReturnService" - {

    "create" - {

      "must evaluate the reporting window at its already-resolved instant" in {
        val now                    = Instant.parse("2026-06-17T12:00:00Z")
        val reportingWindowService = mock[ReportingWindowService]
        val service                = new MonthlyReturnService(
          monthlyReturnRepository = mockMonthlyReturnRepository,
          submissionStoreAction = mockSubStoreAction,
          reportingWindowService = reportingWindowService,
          uuidGenerator = mockUuidGenerator
        )
        when(reportingWindowService.resolve(zReference))
          .thenReturn(Future.successful(ResolvedReportingWindow(now, isOpen = false)))

        service.create(zReference, taxYear, month, nilReturn = false).futureValue mustBe
          CreateMonthlyReturnResult.OutsideDeclarationPeriod

        verify(reportingWindowService).resolve(zReference)
      }

      "must return Created when the repository creates the MonthlyReturn" in {
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

        service
          .create(zReference, taxYear, month, nilReturn = true)
          .futureValue mustBe CreateMonthlyReturnResult.Created(testSubmissionId)
      }

      "must return AlreadyExists with the existing submissionId when the repository rejects the create" in {
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

        service
          .create(zReference, taxYear, month, nilReturn = false)
          .futureValue mustBe CreateMonthlyReturnResult.AlreadyExists(testSubmissionId)
      }

      "must fail when the repository rejects the create but the MonthlyReturn cannot be found" in {
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

        service
          .create(zReference, taxYear, month, nilReturn = false)
          .failed
          .futureValue mustBe a[IllegalStateException]
      }

      "must fail when the repository fails to create the MonthlyReturn" in {
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

        service
          .create(zReference, taxYear, month, nilReturn = false)
          .failed
          .futureValue mustBe a[RuntimeException]
      }

      "must return OutsideDeclarationPeriod if creating a return on April 2026 for April 2026" in {
        val startDate = buildService(Instant.parse(s"2026-04-0${appConfig.declarationPeriodStart}T00:00:00Z"))

        startDate
          .create(zReference, "2025-26", 4, nilReturn = false)
          .futureValue mustBe
          CreateMonthlyReturnResult.OutsideDeclarationPeriod

        verifyNoInteractions(mockMonthlyReturnRepository)
      }

      "must allow creating a return on April 2026 for March 2026 in the 2025-26 tax year" in {
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

        startDate
          .create(zReference, "2025-26", 3, nilReturn = false)
          .futureValue mustBe CreateMonthlyReturnResult.Created(testSubmissionId)
      }

      "must return OutsideDeclarationPeriod if creating a return on April 2026 for the 2027-28 tax year" in {
        val startDate = buildService(Instant.parse(s"2026-04-0${appConfig.declarationPeriodStart}T00:00:00Z"))

        startDate
          .create(zReference, "2027-28", 4, nilReturn = false)
          .futureValue mustBe
          CreateMonthlyReturnResult.OutsideDeclarationPeriod

        verifyNoInteractions(mockMonthlyReturnRepository)
      }

      "must return OutsideDeclarationPeriod if creating a return on July 2026 for August 2026" in {
        val startDate = buildService(Instant.parse(s"2026-07-0${appConfig.declarationPeriodStart}T00:00:00Z"))

        startDate
          .create(zReference, "2026-27", 8, nilReturn = false)
          .futureValue mustBe
          CreateMonthlyReturnResult.OutsideDeclarationPeriod

        verifyNoInteractions(mockMonthlyReturnRepository)
      }

      "must allow creating a return on July 2026 for June 2026" in {
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

        startDate
          .create(zReference, "2026-27", 6, nilReturn = false)
          .futureValue mustBe CreateMonthlyReturnResult.Created(testSubmissionId)
      }
    }

    "get" - {

      "must return Some(monthlyReturn) when the repository finds a monthly return" in {
        when(mockMonthlyReturnRepository.get(eqTo(zReference), eqTo(taxYear), eqTo(month)))
          .thenReturn(Future.successful(Some(monthlyReturn)))

        service.get(zReference, taxYear, month).futureValue mustBe Some(monthlyReturn)
      }

      "must return None when the repository does not find a monthly return" in {
        when(mockMonthlyReturnRepository.get(eqTo(zReference), eqTo(taxYear), eqTo(month)))
          .thenReturn(Future.successful(None))

        service.get(zReference, taxYear, month).futureValue mustBe None
      }

      "must fail when the repository throws an exception" in {
        when(mockMonthlyReturnRepository.get(eqTo(zReference), eqTo(taxYear), eqTo(month)))
          .thenReturn(Future.failed(new RuntimeException(testMongoDownMessage)))

        service.get(zReference, taxYear, month).failed.futureValue mustBe a[RuntimeException]
      }
    }

    "declare with nilReturn false" - {

      "must return Declared when the repository declares the MonthlyReturn" in {
        when(mockMonthlyReturnRepository.declare(eqTo(zReference), eqTo(taxYear), eqTo(month), eqTo(List.empty)))
          .thenReturn(
            Future.successful(DeclareMonthlyReturnRepositoryResult.MonthlyReturnDeclared)
          )

        service
          .declare(zReference, taxYear, month, nilReturn = false)
          .futureValue mustBe DeclareMonthlyReturnResult.Declared

        verify(mockMonthlyReturnRepository).declare(eqTo(zReference), eqTo(taxYear), eqTo(month), eqTo(List.empty))
      }

      "must pass pending submission IDs to the repository" in {
        val pendingSubmissionIds = List(testUploadReference, "second-reference")
        when(
          mockMonthlyReturnRepository.declare(
            eqTo(zReference),
            eqTo(taxYear),
            eqTo(month),
            eqTo(pendingSubmissionIds)
          )
        ).thenReturn(Future.successful(DeclareMonthlyReturnRepositoryResult.MonthlyReturnDeclared))

        service
          .declare(
            zReference,
            taxYear,
            month,
            nilReturn = false,
            pendingSubmissionIds = pendingSubmissionIds
          )
          .futureValue mustBe DeclareMonthlyReturnResult.Declared
      }

      "must return AlreadyDeclared when the repository rejects a duplicate declaration" in {
        when(mockMonthlyReturnRepository.declare(eqTo(zReference), eqTo(taxYear), eqTo(month), eqTo(List.empty)))
          .thenReturn(Future.successful(DeclareMonthlyReturnRepositoryResult.MonthlyReturnAlreadyDeclared))

        service
          .declare(zReference, taxYear, month, nilReturn = false)
          .futureValue mustBe DeclareMonthlyReturnResult.AlreadyDeclared
      }

      "must return MonthlyReturnNotFound when the repository cannot find the MonthlyReturn" in {
        when(mockMonthlyReturnRepository.declare(eqTo(zReference), eqTo(taxYear), eqTo(month), eqTo(List.empty)))
          .thenReturn(Future.successful(DeclareMonthlyReturnRepositoryResult.MonthlyReturnNotFound))

        service
          .declare(zReference, taxYear, month, nilReturn = false)
          .futureValue mustBe DeclareMonthlyReturnResult.MonthlyReturnNotFound
      }

      "must allow declarations from the configured start day" in {
        val startOfDeclarationPeriod = Instant.parse("2026-06-06T00:00:00Z")
        val startDayService          = buildService(startOfDeclarationPeriod)
        when(mockMonthlyReturnRepository.declare(eqTo(zReference), eqTo(taxYear), eqTo(month), eqTo(List.empty)))
          .thenReturn(
            Future.successful(DeclareMonthlyReturnRepositoryResult.MonthlyReturnDeclared)
          )

        startDayService.declare(zReference, taxYear, month, nilReturn = false).futureValue mustBe
          DeclareMonthlyReturnResult.Declared
      }

      "must return OutsideDeclarationPeriod and not call the repository before the configured start day" in {
        val beforeStartDayService = buildService(Instant.parse("2026-06-05T23:59:59Z"))

        beforeStartDayService.declare(zReference, taxYear, month, nilReturn = false).futureValue mustBe
          DeclareMonthlyReturnResult.OutsideDeclarationPeriod

        verifyNoInteractions(mockMonthlyReturnRepository)
      }

      "must allow declarations until the end of the configured end day" in {
        val endOfDeclarationPeriod = Instant.parse("2026-06-19T23:59:59Z")
        val endDayService          = buildService(endOfDeclarationPeriod)
        when(mockMonthlyReturnRepository.declare(eqTo(zReference), eqTo(taxYear), eqTo(month), eqTo(List.empty)))
          .thenReturn(
            Future.successful(DeclareMonthlyReturnRepositoryResult.MonthlyReturnDeclared)
          )

        endDayService.declare(zReference, taxYear, month, nilReturn = false).futureValue mustBe
          DeclareMonthlyReturnResult.Declared
      }

      "must return OutsideDeclarationPeriod and not call the repository after the configured end day" in {
        val afterEndDayService = buildService(Instant.parse("2026-06-20T00:00:00Z"))

        afterEndDayService.declare(zReference, taxYear, month, nilReturn = false).futureValue mustBe
          DeclareMonthlyReturnResult.OutsideDeclarationPeriod

        verifyNoInteractions(mockMonthlyReturnRepository)
      }

      "must return OutsideDeclarationPeriod if declaring on April 2026 for April 2026" in {
        val startDate = buildService(Instant.parse(s"2026-04-0${appConfig.declarationPeriodStart}T00:00:00Z"))

        startDate.declare(zReference, "2025-26", 4, nilReturn = false).futureValue mustBe
          DeclareMonthlyReturnResult.OutsideDeclarationPeriod

        verifyNoInteractions(mockMonthlyReturnRepository)
      }

      "must allow declaring on April 2026 for March 2026 in the 2025-26 tax year" in {
        val startDate = buildService(Instant.parse(s"2026-04-0${appConfig.declarationPeriodStart}T00:00:00Z"))
        when(mockMonthlyReturnRepository.declare(eqTo(zReference), eqTo("2025-26"), eqTo(3), eqTo(List.empty)))
          .thenReturn(Future.successful(DeclareMonthlyReturnRepositoryResult.MonthlyReturnDeclared))

        startDate.declare(zReference, "2025-26", 3, nilReturn = false).futureValue mustBe
          DeclareMonthlyReturnResult.Declared
      }

      "must return OutsideDeclarationPeriod if declaring on April 2026 for the 2027-28 tax year" in {
        val startDate = buildService(Instant.parse(s"2026-04-0${appConfig.declarationPeriodStart}T00:00:00Z"))

        startDate.declare(zReference, "2027-28", 4, nilReturn = false).futureValue mustBe
          DeclareMonthlyReturnResult.OutsideDeclarationPeriod

        verifyNoInteractions(mockMonthlyReturnRepository)
      }

      "must return OutsideDeclarationPeriod if declaring on July 2026 for August 2026" in {
        val startDate = buildService(Instant.parse(s"2026-07-0${appConfig.declarationPeriodStart}T00:00:00Z"))

        startDate
          .declare(zReference, "2026-27", 8, nilReturn = false)
          .futureValue mustBe
          DeclareMonthlyReturnResult.OutsideDeclarationPeriod

        verifyNoInteractions(mockMonthlyReturnRepository)
      }

      "must allow declaring on July 2026 for June 2026" in {
        val startDate = buildService(Instant.parse(s"2026-07-0${appConfig.declarationPeriodStart}T00:00:00Z"))
        when(mockMonthlyReturnRepository.declare(eqTo(zReference), eqTo("2026-27"), eqTo(6), eqTo(List.empty)))
          .thenReturn(Future.successful(DeclareMonthlyReturnRepositoryResult.MonthlyReturnDeclared))

        startDate
          .declare(zReference, "2026-27", 6, nilReturn = false)
          .futureValue mustBe DeclareMonthlyReturnResult.Declared
      }
    }

    "submitReturn" - {
      "must return UpdateSuccessful for a missing submission before declaration" in {

        when(mockMonthlyReturnRepository.get(any(), any(), any()))
          .thenReturn(Future.successful(Some(monthlyReturn)))

        when(mockSubStoreAction.store(any(), any(), eqTo(testUploadReference)))
          .thenReturn(Future.successful(SubmitReturnResult.UpdateSuccessful))

        service
          .storeSubmission(
            zReference,
            taxYear,
            month,
            testUploadReference,
            testTempFile
          )
          .futureValue mustBe SubmitReturnResult.UpdateSuccessful
      }

      "must allow an existing CREATED submission after declaration" in {
        val declaredReturn = monthlyReturn.copy(
          submissions = List(
            Submission(
              reference = testUploadReference,
              status = SubmissionStatus.Created,
              createdOn = testCreatedOn
            )
          ),
          declaredOn = Some(testCreatedOn)
        )
        when(mockMonthlyReturnRepository.get(any(), any(), any()))
          .thenReturn(Future.successful(Some(declaredReturn)))
        when(mockSubStoreAction.store(any(), eqTo(declaredReturn), eqTo(testUploadReference)))
          .thenReturn(Future.successful(SubmitReturnResult.UpdateSuccessful))

        service
          .storeSubmission(zReference, taxYear, month, testUploadReference, testTempFile)
          .futureValue mustBe SubmitReturnResult.UpdateSuccessful
      }

      "must return SubmissionConflict without storing when the submission is already STORED" in {
        val monthlyReturnWithStoredSubmission = monthlyReturn.copy(
          submissions = List(
            Submission(
              reference = testUploadReference,
              status = SubmissionStatus.Stored,
              createdOn = testCreatedOn,
              submissionDetails = Some(fileUploadDetails)
            )
          )
        )
        when(mockMonthlyReturnRepository.get(any(), any(), any()))
          .thenReturn(Future.successful(Some(monthlyReturnWithStoredSubmission)))

        service
          .storeSubmission(zReference, taxYear, month, testUploadReference, testTempFile)
          .futureValue mustBe SubmitReturnResult.SubmissionConflict

        verifyNoInteractions(mockSubStoreAction)
      }

      "must return SubmissionConflict for a missing submission after declaration" in {
        when(mockMonthlyReturnRepository.get(any(), any(), any()))
          .thenReturn(Future.successful(Some(monthlyReturn.copy(declaredOn = Some(testCreatedOn)))))

        service
          .storeSubmission(zReference, taxYear, month, testUploadReference, testTempFile)
          .futureValue mustBe SubmitReturnResult.SubmissionConflict

        verifyNoInteractions(mockSubStoreAction)
      }

      "must return SubmissionConflict if the state changes while storing" in {
        when(mockMonthlyReturnRepository.get(any(), any(), any()))
          .thenReturn(Future.successful(Some(monthlyReturn)))
        when(mockSubStoreAction.store(any(), any(), eqTo(testUploadReference)))
          .thenReturn(Future.successful(SubmitReturnResult.SubmissionConflict))

        service
          .storeSubmission(zReference, taxYear, month, testUploadReference, testTempFile)
          .futureValue mustBe SubmitReturnResult.SubmissionConflict
      }

      "must return MonthlyReturnNotFound if a Monthly return hasn't been created yet" in {

        when(mockMonthlyReturnRepository.get(any(), any(), any()))
          .thenReturn(Future.successful(None))

        service
          .storeSubmission(
            zReference,
            taxYear,
            month,
            testUploadReference,
            testTempFile
          )
          .futureValue mustBe SubmitReturnResult.MonthlyReturnNotFound

        verifyNoInteractions(mockSubStoreAction)

      }

      "must fail when the submission store action fails" in {
        when(mockMonthlyReturnRepository.get(any(), any(), any()))
          .thenReturn(Future.successful(Some(monthlyReturn)))

        when(mockSubStoreAction.store(any(), any(), eqTo(testUploadReference)))
          .thenReturn(Future.failed(new RuntimeException(testMongoDownMessage)))

        service
          .storeSubmission(
            zReference,
            taxYear,
            month,
            testUploadReference,
            testTempFile
          )
          .failed
          .futureValue mustBe a[RuntimeException]
      }
    }

    "declare with nilReturn true" - {

      "must reject pending submission IDs without calling the repository" in {
        service
          .declare(
            zReference,
            taxYear,
            month,
            nilReturn = true,
            pendingSubmissionIds = List(testUploadReference)
          )
          .futureValue mustBe DeclareMonthlyReturnResult.PendingSubmissionsForNilReturn

        verifyNoInteractions(mockMonthlyReturnRepository)
      }

      "must return Declared when no monthly return exists and one is created" in {
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

        service
          .declare(zReference, taxYear, month, nilReturn = true)
          .futureValue mustBe DeclareMonthlyReturnResult.Declared

        verify(mockUuidGenerator).randomUuid()
        verify(mockMonthlyReturnRepository).declare(eqTo(zReference), eqTo(taxYear), eqTo(month), eqTo(List.empty))
      }

      "must return AlreadyDeclared when created nil return is already declared before fallback declaration completes" in {
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

        service
          .declare(zReference, taxYear, month, nilReturn = true)
          .futureValue mustBe DeclareMonthlyReturnResult.AlreadyDeclared
      }

      "must return MonthlyReturnNotFound when created nil return cannot be found by fallback declaration" in {
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

        service
          .declare(zReference, taxYear, month, nilReturn = true)
          .futureValue mustBe DeclareMonthlyReturnResult.MonthlyReturnNotFound
      }

      "must return Declared and update existing monthly return to nil return" in {
        when(mockMonthlyReturnRepository.declareNilReturn(eqTo(zReference), eqTo(taxYear), eqTo(month)))
          .thenReturn(Future.successful(DeclareMonthlyReturnRepositoryResult.MonthlyReturnDeclared))

        service
          .declare(zReference, taxYear, month, nilReturn = true)
          .futureValue mustBe DeclareMonthlyReturnResult.Declared

        verify(mockMonthlyReturnRepository).declareNilReturn(eqTo(zReference), eqTo(taxYear), eqTo(month))
        verify(mockUuidGenerator, never()).randomUuid()
        verify(mockMonthlyReturnRepository, never()).create(any(), any(), any(), any(), any())
        verify(mockMonthlyReturnRepository, never()).declare(any(), any(), any(), any())
      }

      "must return AlreadyDeclared when existing monthly return is already declared" in {
        when(mockMonthlyReturnRepository.declareNilReturn(eqTo(zReference), eqTo(taxYear), eqTo(month)))
          .thenReturn(Future.successful(DeclareMonthlyReturnRepositoryResult.MonthlyReturnAlreadyDeclared))

        service
          .declare(zReference, taxYear, month, nilReturn = true)
          .futureValue mustBe DeclareMonthlyReturnResult.AlreadyDeclared

        verify(mockUuidGenerator, never()).randomUuid()
        verify(mockMonthlyReturnRepository, never()).create(any(), any(), any(), any(), any())
        verify(mockMonthlyReturnRepository, never()).declare(any(), any(), any(), any())
      }

      "must return OutsideDeclarationPeriod when outside declaration period" in {
        val beforeStartDayService = buildService(Instant.parse("2026-06-05T23:59:59Z"))

        beforeStartDayService.declare(zReference, taxYear, month, nilReturn = true).futureValue mustBe
          DeclareMonthlyReturnResult.OutsideDeclarationPeriod

        verifyNoInteractions(mockMonthlyReturnRepository)
      }

      "must fail when no monthly return exists and create returns None" in {
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

        service.declare(zReference, taxYear, month, nilReturn = true).failed.futureValue mustBe a[RuntimeException]
      }

      "must fail when repository throws an exception" in {
        when(mockMonthlyReturnRepository.declareNilReturn(eqTo(zReference), eqTo(taxYear), eqTo(month)))
          .thenReturn(Future.failed(new RuntimeException("mongodb down")))

        service.declare(zReference, taxYear, month, nilReturn = true).failed.futureValue mustBe a[RuntimeException]
      }

    }
  }

  private def buildService(now: Instant): MonthlyReturnService = {
    val timeSource: TimeSource = (_: String) => Future.successful(now)
    new MonthlyReturnService(
      monthlyReturnRepository = mockMonthlyReturnRepository,
      mockSubStoreAction,
      reportingWindowService = new ReportingWindowService(appConfig, timeSource),
      uuidGenerator = mockUuidGenerator
    )
  }
}
