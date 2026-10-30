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

package uk.gov.hmrc.disareturnssubmission

import base.SpecBase
import play.api.test.Helpers.*
import uk.gov.hmrc.disareturnssubmission.services.*
import uk.gov.hmrc.disareturnssubmission.testOnly.OverrideTimeSource
import uk.gov.hmrc.disareturnssubmission.testOnly.services.OverrideMonthlyReturnReportingWindowService
import uk.gov.hmrc.disareturnssubmission.validators.*

class ModuleSpec extends SpecBase {

  "Module" - {

    "must bind production services when test-only routes are disabled" in {
      inject[MonthlyReturnReportingWindowService].getClass mustBe classOf[MonthlyReturnReportingWindowService]
      inject[TimeSource] mustBe a[SystemClock]
    }

    "must bind override services when test-only routes are enabled" in {
      val overrideApp = applicationBuilder()
        .configure("application.router" -> "testOnlyDoNotUseInAppConf.Routes")
        .build()

      try {
        overrideApp.injector
          .instanceOf[MonthlyReturnReportingWindowService] mustBe a[OverrideMonthlyReturnReportingWindowService]
        overrideApp.injector.instanceOf[TimeSource] mustBe a[OverrideTimeSource]
      } finally await(overrideApp.stop())
    }

    "must bind strict Z-reference validation by default" in {
      inject[ZReferenceValidator] mustBe a[StrictZReferenceValidator]
    }

    "must bind loose Z-reference validation when strict validation is disabled" in {
      val looseApp = applicationBuilder()
        .configure("features.strict-z-reference-validation-enabled" -> false)
        .build()

      try looseApp.injector.instanceOf[ZReferenceValidator] mustBe a[LooseZReferenceValidator]
      finally await(looseApp.stop())
    }
  }
}
