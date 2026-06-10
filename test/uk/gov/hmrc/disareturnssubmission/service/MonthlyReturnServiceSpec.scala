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
import org.mockito.ArgumentMatchers.eq as eqTo
import org.mockito.Mockito.{reset, verify, verifyNoInteractions, when}
import org.scalatest.BeforeAndAfterEach
import uk.gov.hmrc.disareturnssubmission.config.AppConfig
import uk.gov.hmrc.disareturnssubmission.models.*
import uk.gov.hmrc.disareturnssubmission.repositories.{DeclareMonthlyReturnRepositoryResult, MonthlyReturnRepository}
import uk.gov.hmrc.disareturnssubmission.services.CreateMonthlyReturnResult.{AlreadyExists, Created}
import uk.gov.hmrc.disareturnssubmission.services.{DeclareMonthlyReturnResult, MonthlyReturnService, UuidGenerator}

import java.time.{Clock, Instant, ZoneOffset}
import scala.concurrent.Future
class MonthlyReturnServiceSpec extends SpecBase with BeforeAndAfterEach {

  private val mockMonthlyReturnRepository = mock[MonthlyReturnRepository]
  private val appConfig                   = inject[AppConfig]
  private val mockUuidGenerator           = mock[UuidGenerator]
  private val service                     = buildService(testCreatedOn)

  private val zReference      = testZReference
  private val taxYear         = testTaxYear
  private val month           = testMonth
  private val uploadReference = testUploadReference

  private val monthlyReturn = MonthlyReturn(
    zReference = zReference,
    submissionId = testSubmissionId,
    taxYear = taxYear,
    month = month,
    createdOn = testExistingUpdatedOn,
    nilReturn = false,
    fileUploads = Nil,
    lastUpdated = testCreatedOn
  )

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    reset(mockMonthlyReturnRepository)
    reset(mockUuidGenerator)
  }

  "MonthlyReturnService" - {

    "get" - {

      "must return the repository result" in {
        when(mockMonthlyReturnRepository.get(eqTo(zReference), eqTo(taxYear), eqTo(month)))
          .thenReturn(Future.successful(Some(monthlyReturn)))

        service.get(zReference, taxYear, month).futureValue mustBe Some(monthlyReturn)
      }
    }

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

        service.create(zReference, taxYear, month, nilReturn = true).futureValue mustBe Created(testSubmissionId)
      }

      "must return AlreadyExists when the repository rejects the create" in {
        when(mockUuidGenerator.randomUuid()).thenReturn(testSubmissionId)
        when(
          mockMonthlyReturnRepository.create(
            eqTo(zReference),
            eqTo(taxYear),
            eqTo(month),
            eqTo(testSubmissionId),
            eqTo(false)
          )
        )
          .thenReturn(Future.successful(None))

        service.create(zReference, taxYear, month, nilReturn = false).futureValue mustBe AlreadyExists
      }
    }

    "declare" - {

      "must return Declared when the repository declares the MonthlyReturn" in {
        when(mockMonthlyReturnRepository.declare(eqTo(zReference), eqTo(taxYear), eqTo(month)))
          .thenReturn(
            Future.successful(DeclareMonthlyReturnRepositoryResult.MonthlyReturnDeclared)
          )

        service.declare(zReference, taxYear, month).futureValue mustBe DeclareMonthlyReturnResult.Declared

        verify(mockMonthlyReturnRepository).declare(eqTo(zReference), eqTo(taxYear), eqTo(month))
      }

      "must return AlreadyDeclared when the repository rejects a duplicate declaration" in {
        when(mockMonthlyReturnRepository.declare(eqTo(zReference), eqTo(taxYear), eqTo(month)))
          .thenReturn(Future.successful(DeclareMonthlyReturnRepositoryResult.MonthlyReturnAlreadyDeclared))

        service.declare(zReference, taxYear, month).futureValue mustBe DeclareMonthlyReturnResult.AlreadyDeclared
      }

      "must return MonthlyReturnNotFound when the repository cannot find the MonthlyReturn" in {
        when(mockMonthlyReturnRepository.declare(eqTo(zReference), eqTo(taxYear), eqTo(month)))
          .thenReturn(Future.successful(DeclareMonthlyReturnRepositoryResult.MonthlyReturnNotFound))

        service.declare(zReference, taxYear, month).futureValue mustBe DeclareMonthlyReturnResult.MonthlyReturnNotFound
      }

      "must allow declarations from the configured start day" in {
        val startOfDeclarationPeriod = Instant.parse("2026-05-06T00:00:00Z")
        val startDayService          = buildService(startOfDeclarationPeriod)
        when(mockMonthlyReturnRepository.declare(eqTo(zReference), eqTo(taxYear), eqTo(month)))
          .thenReturn(
            Future.successful(DeclareMonthlyReturnRepositoryResult.MonthlyReturnDeclared)
          )

        startDayService.declare(zReference, taxYear, month).futureValue mustBe
          DeclareMonthlyReturnResult.Declared
      }

      "must return OutsideDeclarationPeriod and not call the repository before the configured start day" in {
        val beforeStartDayService = buildService(Instant.parse("2026-05-05T23:59:59Z"))

        beforeStartDayService.declare(zReference, taxYear, month).futureValue mustBe
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

        endDayService.declare(zReference, taxYear, month).futureValue mustBe
          DeclareMonthlyReturnResult.Declared
      }

      "must return OutsideDeclarationPeriod and not call the repository after the configured end day" in {
        val afterEndDayService = buildService(Instant.parse("2026-05-20T00:00:00Z"))

        afterEndDayService.declare(zReference, taxYear, month).futureValue mustBe
          DeclareMonthlyReturnResult.OutsideDeclarationPeriod

        verifyNoInteractions(mockMonthlyReturnRepository)
      }
    }

  }

  private def buildService(now: Instant): MonthlyReturnService =
    new MonthlyReturnService(
      monthlyReturnRepository = mockMonthlyReturnRepository,
      appConfig = appConfig,
      clock = Clock.fixed(now, ZoneOffset.UTC),
      uuidGenerator = mockUuidGenerator
    )
}
