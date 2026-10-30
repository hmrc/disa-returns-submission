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

import config.*
import play.api.*
import play.api.inject.{Module as AppModule, bind as binding, *}
import services.*
import testOnly.services.OverrideMonthlyReturnReportingWindowService
import uk.gov.hmrc.disareturnssubmission.testOnly.OverrideTimeSource
import uk.gov.hmrc.disareturnssubmission.jobs.MonthlyReturnSubmissionWorkItemJob
import utils.*
import validators.*

import java.time.*

class Module extends AppModule:

  override def bindings(
    environment: Environment,
    configuration: Configuration
  ): Seq[Binding[_]] = {

    val authTokenInitialiserBindings: Seq[Binding[?]] =
      if (configuration.get[Boolean]("create-internal-auth-token-on-start")) {
        Seq(
          binding[InternalAuthTokenInitialiser]
            .to[InternalAuthTokenInitialiserImpl]
        )
      } else {
        Seq(
          binding[InternalAuthTokenInitialiser]
            .to[NoOpInternalAuthTokenInitialiser]
        )
      }

    val testOnlyRoutesEnabled =
      configuration.getOptional[String]("application.router").contains("testOnlyDoNotUseInAppConf.Routes")

    val zReferenceValidatorBinding =
      if (configuration.get[Boolean]("features.strict-z-reference-validation-enabled")) {
        binding[ZReferenceValidator].to[StrictZReferenceValidator]
      } else {
        binding[ZReferenceValidator].to[LooseZReferenceValidator]
      }

    val overrideBindings: Seq[Binding[?]] =
      if (testOnlyRoutesEnabled) {
        Seq(
          binding[OverrideTimeSource].toSelf,
          binding[TimeSource].to[OverrideTimeSource],
          binding[MonthlyReturnReportingWindowService].to[OverrideMonthlyReturnReportingWindowService]
        )
      } else {
        Seq(
          binding[TimeSource].to[SystemClock],
          binding[MonthlyReturnReportingWindowService].toSelf
        )
      }

    Seq(
      binding[AppInitialiser].toSelf.eagerly(),
      binding[MonthlyReturnSubmissionWorkItemJob].toSelf,
      binding[Clock].to(Clock.systemDefaultZone.withZone(ZoneOffset.UTC)),
      binding[NdjsonRecordCounter].to[NdjsonRecordCounterImpl],
      zReferenceValidatorBinding
    ) ++ overrideBindings ++ authTokenInitialiserBindings
  }
