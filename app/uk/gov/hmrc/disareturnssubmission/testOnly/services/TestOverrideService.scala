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

package uk.gov.hmrc.disareturnssubmission.testOnly.services

import uk.gov.hmrc.disareturnssubmission.testOnly.models.{TestOverride, TestOverrideDocument, TestOverrideRequest}
import uk.gov.hmrc.disareturnssubmission.testOnly.repositories.TestOverrideRepository

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class TestOverrideService @Inject() (
  repository: TestOverrideRepository
)(implicit ec: ExecutionContext) {

  def get(zReference: String): Future[TestOverride] =
    repository.getActive(zReference).map(_.fold(empty(zReference))(toApi))

  def replace(zReference: String, request: TestOverrideRequest): Future[TestOverride] =
    repository.replace(zReference, request).map(toApi)

  def delete(zReference: String): Future[TestOverride] =
    repository.delete(zReference).map(_ => empty(zReference))

  private def toApi(document: TestOverrideDocument): TestOverride =
    TestOverride(document._id, document.clock, document.reportingWindow)

  private def empty(zReference: String): TestOverride =
    TestOverride(zReference, None, None)
}
