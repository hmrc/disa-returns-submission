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

package uk.gov.hmrc.disareturnssubmission.validators

import base.SpecBase

class ValidationHelperSpec extends SpecBase {

  private val strictValidator  = new StrictZReferenceValidator
  private val looseValidator   = new LooseZReferenceValidator
  private val validationHelper = new ValidationHelper(strictValidator)

  "ZReferenceValidator.isValid" - {

    "must return true for a valid upper or lower case zReference" in {
      strictValidator.isValid(testZReference) mustBe true
      strictValidator.isValid(lowercaseTestZReference) mustBe true
    }

    "must return false for an invalid zReference" in {
      strictValidator.isValid(invalidTestZReference) mustBe false
      strictValidator.isValid("Z12345") mustBe false
      strictValidator.isValid(null) mustBe false
    }

    "must support four to eight digits in loose mode" in {
      looseValidator.isValid("Z1234") mustBe true
      looseValidator.isValid("z12345678") mustBe true
      looseValidator.isValid("Z123") mustBe false
      looseValidator.isValid("Z123456789") mustBe false
    }

    "must normalize with Locale-independent uppercase and preserved trimming" in {
      strictValidator.normalize("  z1234  ") mustBe Some("Z1234")
      strictValidator.normalize(null) mustBe None
    }
  }

  "TaxYearValidator.isValid" - {

    "must return true for a valid tax year" in {
      TaxYearValidator.isValid(testTaxYear) mustBe true
    }

    "must return false for an invalid tax year" in {
      TaxYearValidator.isValid("2026-28") mustBe false
      TaxYearValidator.isValid(invalidTestTaxYear) mustBe false
      TaxYearValidator.isValid(null) mustBe false
    }
  }

  "MonthValidator.isValid" - {

    "must return true for a valid month" in {
      MonthValidator.isValid(1) mustBe true
      MonthValidator.isValid(12) mustBe true
    }

    "must return false for an invalid month" in {
      MonthValidator.isValid(0) mustBe false
      MonthValidator.isValid(invalidTestMonth) mustBe false
      MonthValidator.isValid(13) mustBe false
    }

  }

  "ValidationHelper.validateParams" - {

    "must normalise valid path parameters" in {
      validationHelper.validateParams(lowercaseTestZReference, testTaxYear, testMonth) mustBe
        Right((testZReference, testTaxYear, testMonth))
    }

    "must return all invalid field names" in {
      validationHelper.validateParams(invalidTestZReference, invalidTestTaxYear, invalidTestMonth) mustBe Left(
        s"Invalid monthly return submission fields: [$zReferenceFieldName, $taxYearFieldName, $monthFieldName]"
      )
    }

    "must retain non-trimming validation for monthly path parameters" in {
      validationHelper.validateParams(s" $testZReference ", testTaxYear, testMonth) mustBe Left(
        s"Invalid monthly return submission fields: [$zReferenceFieldName]"
      )
    }
  }
}
