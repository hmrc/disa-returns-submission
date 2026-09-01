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

import config.{InternalAuthTokenInitialiser, InternalAuthTokenInitialiserImpl, NoOpInternalAuthTokenInitialiser}
import play.api.{Configuration, Environment}
import play.api.inject.{Binding, Module as AppModule, bind as binding}
import services.{ReportingWindowService, SystemClock, TimeSource}
import testOnly.services.MutableReportingWindowService
import uk.gov.hmrc.disareturnssubmission.testOnly.OverridableClock

import java.time.{Clock, ZoneOffset}

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

    val overrideBindings: Seq[Binding[?]] =
      if (testOnlyRoutesEnabled) {
        Seq(
          binding[OverridableClock].toSelf,
          binding[TimeSource].to[OverridableClock],
          binding[ReportingWindowService].to[MutableReportingWindowService]
        )
      } else {
        Seq(
          binding[TimeSource].to[SystemClock],
          binding[ReportingWindowService].toSelf
        )
      }

    Seq(
      binding[AppInitialiser].toSelf.eagerly(),
      binding[Clock].to(Clock.systemDefaultZone.withZone(ZoneOffset.UTC))
    ) ++ overrideBindings ++ authTokenInitialiserBindings
  }
