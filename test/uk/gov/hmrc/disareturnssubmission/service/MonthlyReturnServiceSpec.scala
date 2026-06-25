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
import org.mockito.Mockito.{reset, verify, verifyNoInteractions, when}
import org.scalatest.BeforeAndAfterEach
import uk.gov.hmrc.disareturnssubmission.actions.SubmissionStoreAction
import uk.gov.hmrc.disareturnssubmission.config.AppConfig
import uk.gov.hmrc.disareturnssubmission.models.*
import uk.gov.hmrc.disareturnssubmission.repositories.{DeclareMonthlyReturnRepositoryResult, MonthlyReturnRepository}
import uk.gov.hmrc.disareturnssubmission.services.{CreateMonthlyReturnResult, DeclareMonthlyReturnResult, MonthlyReturnService, SubmitReturnResult}
import uk.gov.hmrc.disareturnssubmission.utils.UuidGenerator

import java.time.{Clock, Instant, ZoneOffset}
import java.util.UUID
import scala.concurrent.Future
class MonthlyReturnServiceSpec extends SpecBase with BeforeAndAfterEach {

  private val mockMonthlyReturnRepository = mock[MonthlyReturnRepository]
  private val mockSubStoreAction          = mock[SubmissionStoreAction]
  private val appConfig                   = inject[AppConfig]
  private val mockUuidGenerator           = mock[UuidGenerator]
  private val service                     = buildService(testCreatedOn)

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
    reset(mockUuidGenerator)
  }

  "MonthlyReturnService" - {

    "create" - {

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

      "must return OutsideDeclarationPeriod if creating a return on April 2026 for the 2025-26 tax year" in {
        val startDate = buildService(Instant.parse(s"2026-04-0${appConfig.declarationPeriodStart}T00:00:00Z"))

        startDate
          .create(zReference, "2025-26", 4, nilReturn = false)
          .futureValue mustBe
          CreateMonthlyReturnResult.OutsideDeclarationPeriod

        verifyNoInteractions(mockMonthlyReturnRepository)
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

      "must return OutsideDeclarationPeriod if creating a return on July 2026 for June 2026" in {
        val startDate = buildService(Instant.parse(s"2026-07-0${appConfig.declarationPeriodStart}T00:00:00Z"))

        startDate
          .create(zReference, "2026-27", 6, nilReturn = false)
          .futureValue mustBe
          CreateMonthlyReturnResult.OutsideDeclarationPeriod

        verifyNoInteractions(mockMonthlyReturnRepository)
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
        when(mockMonthlyReturnRepository.declare(eqTo(zReference), eqTo(taxYear), eqTo(month)))
          .thenReturn(
            Future.successful(DeclareMonthlyReturnRepositoryResult.MonthlyReturnDeclared)
          )

        service
          .declare(zReference, taxYear, month, nilReturn = false)
          .futureValue mustBe DeclareMonthlyReturnResult.Declared

        verify(mockMonthlyReturnRepository).declare(eqTo(zReference), eqTo(taxYear), eqTo(month))
      }

      "must return AlreadyDeclared when the repository rejects a duplicate declaration" in {
        when(mockMonthlyReturnRepository.declare(eqTo(zReference), eqTo(taxYear), eqTo(month)))
          .thenReturn(Future.successful(DeclareMonthlyReturnRepositoryResult.MonthlyReturnAlreadyDeclared))

        service
          .declare(zReference, taxYear, month, nilReturn = false)
          .futureValue mustBe DeclareMonthlyReturnResult.AlreadyDeclared
      }

      "must return MonthlyReturnNotFound when the repository cannot find the MonthlyReturn" in {
        when(mockMonthlyReturnRepository.declare(eqTo(zReference), eqTo(taxYear), eqTo(month)))
          .thenReturn(Future.successful(DeclareMonthlyReturnRepositoryResult.MonthlyReturnNotFound))

        service
          .declare(zReference, taxYear, month, nilReturn = false)
          .futureValue mustBe DeclareMonthlyReturnResult.MonthlyReturnNotFound
      }

      "must allow declarations from the configured start day" in {
        val startOfDeclarationPeriod = Instant.parse("2026-05-06T00:00:00Z")
        val startDayService          = buildService(startOfDeclarationPeriod)
        when(mockMonthlyReturnRepository.declare(eqTo(zReference), eqTo(taxYear), eqTo(month)))
          .thenReturn(
            Future.successful(DeclareMonthlyReturnRepositoryResult.MonthlyReturnDeclared)
          )

        startDayService.declare(zReference, taxYear, month, nilReturn = false).futureValue mustBe
          DeclareMonthlyReturnResult.Declared
      }

      "must return OutsideDeclarationPeriod and not call the repository before the configured start day" in {
        val beforeStartDayService = buildService(Instant.parse("2026-05-05T23:59:59Z"))

        beforeStartDayService.declare(zReference, taxYear, month, nilReturn = false).futureValue mustBe
          DeclareMonthlyReturnResult.OutsideDeclarationPeriod

        verifyNoInteractions(mockMonthlyReturnRepository)
      }

      "must allow declarations until the end of the configured end day" in {
        val endOfDeclarationPeriod = Instant.parse("2026-05-19T23:59:59Z")
        val endDayService          = buildService(endOfDeclarationPeriod)
        when(mockMonthlyReturnRepository.declare(eqTo(zReference), eqTo(taxYear), eqTo(month)))
          .thenReturn(
            Future.successful(DeclareMonthlyReturnRepositoryResult.MonthlyReturnDeclared)
          )

        endDayService.declare(zReference, taxYear, month, nilReturn = false).futureValue mustBe
          DeclareMonthlyReturnResult.Declared
      }

      "must return OutsideDeclarationPeriod and not call the repository after the configured end day" in {
        val afterEndDayService = buildService(Instant.parse("2026-05-20T00:00:00Z"))

        afterEndDayService.declare(zReference, taxYear, month, nilReturn = false).futureValue mustBe
          DeclareMonthlyReturnResult.OutsideDeclarationPeriod

        verifyNoInteractions(mockMonthlyReturnRepository)
      }

      "must return OutsideDeclarationPeriod if declaring on April 2026 for the 2025-26 tax year" in {
        val startDate = buildService(Instant.parse(s"2026-04-0${appConfig.declarationPeriodStart}T00:00:00Z"))

        startDate.declare(zReference, "2025-26", 4, nilReturn = false).futureValue mustBe
          DeclareMonthlyReturnResult.OutsideDeclarationPeriod

        verifyNoInteractions(mockMonthlyReturnRepository)
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

      "must return OutsideDeclarationPeriod if declaring on July 2026 for June 2026" in {
        val startDate = buildService(Instant.parse(s"2026-07-0${appConfig.declarationPeriodStart}T00:00:00Z"))

        startDate
          .declare(zReference, "2026-27", 6, nilReturn = false)
          .futureValue mustBe
          DeclareMonthlyReturnResult.OutsideDeclarationPeriod

        verifyNoInteractions(mockMonthlyReturnRepository)
      }
    }

    "submitReturn" - {
      "must return UpdateSuccessful" in {

        when(mockMonthlyReturnRepository.get(any(), any(), any()))
          .thenReturn(Future.successful(Some(monthlyReturn)))

        when(mockMonthlyReturnRepository.upsert(any()))
          .thenReturn(Future.successful(true))

        when(mockSubStoreAction.store(any(), any()))
          .thenReturn(Future.successful(SubmitReturnResult.UpdateSuccessful))

        service
          .storeSubmission(
            zReference,
            taxYear,
            month,
            testTempFile
          )
          .futureValue mustBe SubmitReturnResult.UpdateSuccessful

      }

      "must return NotUpdatedInRepository if repository fails to update" in {

        when(mockMonthlyReturnRepository.get(any(), any(), any()))
          .thenReturn(Future.successful(Some(monthlyReturn)))

        when(mockMonthlyReturnRepository.upsert(any()))
          .thenReturn(Future.successful(false))

        when(mockSubStoreAction.store(any(), any()))
          .thenReturn(Future.successful(SubmitReturnResult.NotUpdatedInRepository))

        service
          .storeSubmission(
            zReference,
            taxYear,
            month,
            testTempFile
          )
          .futureValue mustBe SubmitReturnResult.NotUpdatedInRepository

      }

      "must return MonthlyReturnNotFound if a Monthly return hasn't been created yet" in {

        when(mockMonthlyReturnRepository.get(any(), any(), any()))
          .thenReturn(Future.successful(None))

        service
          .storeSubmission(
            zReference,
            taxYear,
            month,
            testTempFile
          )
          .futureValue mustBe SubmitReturnResult.MonthlyReturnNotFound

      }

    "declare with nilReturn true" - {

      val existingReturnWithUploads = monthlyReturn.copy(
        fileUploads = List(
          uk.gov.hmrc.disareturnssubmission.models.FileUpload(reference = "ref-1", createdOn = testExistingUpdatedOn)
        )
      )

      val declaredReturn = monthlyReturn.copy(declaredOn = Some(testCreatedOn))

      "must return Declared when no monthly return exists and one is created" in {
        when(mockMonthlyReturnRepository.get(eqTo(zReference), eqTo(taxYear), eqTo(month)))
          .thenReturn(Future.successful(None))
        when(
          mockMonthlyReturnRepository.create(
            eqTo(zReference),
            eqTo(taxYear),
            eqTo(month),
            org.mockito.ArgumentMatchers.any[java.util.UUID],
            eqTo(true)
          )
        )
          .thenReturn(Future.successful(Some(monthlyReturn.copy(nilReturn = true))))
        when(mockMonthlyReturnRepository.declare(eqTo(zReference), eqTo(taxYear), eqTo(month)))
          .thenReturn(Future.successful(DeclareMonthlyReturnRepositoryResult.MonthlyReturnDeclared))

        service
          .declare(zReference, taxYear, month, nilReturn = true)
          .futureValue mustBe DeclareMonthlyReturnResult.Declared
      }

      "must return Declared and update existing monthly return to nil return" in {
        when(mockMonthlyReturnRepository.get(eqTo(zReference), eqTo(taxYear), eqTo(month)))
          .thenReturn(Future.successful(Some(existingReturnWithUploads)))
        when(mockMonthlyReturnRepository.upsert(org.mockito.ArgumentMatchers.any[MonthlyReturn]))
          .thenReturn(Future.successful(true))

        service
          .declare(zReference, taxYear, month, nilReturn = true)
          .futureValue mustBe DeclareMonthlyReturnResult.Declared

        verify(mockMonthlyReturnRepository).upsert(org.mockito.ArgumentMatchers.any[MonthlyReturn])
      }

      "must return AlreadyDeclared when existing monthly return is already declared" in {
        when(mockMonthlyReturnRepository.get(eqTo(zReference), eqTo(taxYear), eqTo(month)))
          .thenReturn(Future.successful(Some(declaredReturn)))

        service
          .declare(zReference, taxYear, month, nilReturn = true)
          .futureValue mustBe DeclareMonthlyReturnResult.AlreadyDeclared
      }

      "must return OutsideDeclarationPeriod when outside declaration period" in {
        val beforeStartDayService = buildService(Instant.parse("2026-05-05T23:59:59Z"))

        beforeStartDayService.declare(zReference, taxYear, month, nilReturn = true).futureValue mustBe
          DeclareMonthlyReturnResult.OutsideDeclarationPeriod

        verifyNoInteractions(mockMonthlyReturnRepository)
      }

      "must fail when no monthly return exists and create returns None" in {
        when(mockMonthlyReturnRepository.get(eqTo(zReference), eqTo(taxYear), eqTo(month)))
          .thenReturn(Future.successful(None))
        when(
          mockMonthlyReturnRepository.create(
            eqTo(zReference),
            eqTo(taxYear),
            eqTo(month),
            org.mockito.ArgumentMatchers.any[java.util.UUID],
            eqTo(true)
          )
        )
          .thenReturn(Future.successful(None))

        service.declare(zReference, taxYear, month, nilReturn = true).failed.futureValue mustBe a[RuntimeException]
      }

      "must fail when repository throws an exception" in {
        when(mockMonthlyReturnRepository.get(eqTo(zReference), eqTo(taxYear), eqTo(month)))
          .thenReturn(Future.failed(new RuntimeException("mongodb down")))

        service.declare(zReference, taxYear, month, nilReturn = true).failed.futureValue mustBe a[RuntimeException]
      }

    }

  }

  private def buildService(now: Instant): MonthlyReturnService =
    new MonthlyReturnService(
      monthlyReturnRepository = mockMonthlyReturnRepository,
      mockSubStoreAction,
      appConfig = appConfig,
      clock = Clock.fixed(now, ZoneOffset.UTC),
      uuidGenerator = mockUuidGenerator
    )
}
