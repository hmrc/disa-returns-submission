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

package uk.gov.hmrc.disareturnssubmission.models.monthly
import play.api.libs.json.*

import java.time.LocalDate
import uk.gov.hmrc.disareturnssubmission.models.enums.*

case class MonthlyReturnNpsSubmission(
  accountIdentifier: String,
  closureDate: Option[LocalDate],
  dateOfBirth: LocalDate,
  firstForename: String,
  firstSubscription: Option[LocalDate],
  flexibleIsa: Option[Boolean],
  isaClosureReason: Option[IsaClosureReason] = None,
  isaType: IsaType,
  lastSubscription: LocalDate,
  lisaBonusClaim: Option[BigDecimal] = None,
  lisaClosureReason: Option[LisaClosureReason] = None,
  lisaQualifyingAddition: Option[BigDecimal],
  nationalInsuranceNumberAndSuffix: String,
  secondForename: Option[String] = None,
  surname: String,
  totalSubscriptions: BigDecimal,
  totalTransferredIn: BigDecimal,
  totalTransferredOut: BigDecimal
)

object MonthlyReturnNpsSubmission {
  implicit val format: OFormat[MonthlyReturnNpsSubmission] = Json.format[MonthlyReturnNpsSubmission]

  def fromNdJson(submission: MonthlyReturnNdJsonSubmission): MonthlyReturnNpsSubmission = {
    val c = submission.common

    val base = MonthlyReturnNpsSubmission(
      accountIdentifier = c.accountNumber,
      closureDate = None,
      dateOfBirth = c.dateOfBirth,
      firstForename = c.firstName,
      firstSubscription = None,
      flexibleIsa = None,
      isaClosureReason = None,
      isaType = c.isaType,
      lastSubscription = c.dateOfLastSubscription,
      lisaBonusClaim = None,
      lisaClosureReason = None,
      lisaQualifyingAddition = None,
      nationalInsuranceNumberAndSuffix = c.nino,
      secondForename = c.middleName,
      surname = c.lastName,
      totalSubscriptions = c.totalCurrentYearSubscriptionsToDate,
      totalTransferredIn = c.amountTransferredIn,
      totalTransferredOut = c.amountTransferredOut
    )

    submission match {
      case s: MonthlyReturnNdJsonStandardSubscription =>
        base.copy(flexibleIsa = Some(s.flexibleIsa))

      case s: MonthlyReturnNdJsonStandardClosure =>
        base.copy(
          flexibleIsa = Some(s.flexibleIsa),
          closureDate = Some(s.closureDate),
          isaClosureReason = Some(s.reasonForClosure) // see note below
        )

      case s: MonthlyReturnNdJsonLifetimeSubscription =>
        base.copy(
          firstSubscription = Some(s.dateOfFirstSubscription),
          lisaQualifyingAddition = Some(s.lisaQualifyingAddition),
          lisaBonusClaim = Some(s.lisaBonusClaim)
        )

      case s: MonthlyReturnNdJsonLifetimeClosure =>
        base.copy(
          firstSubscription = Some(s.dateOfFirstSubscription),
          lisaQualifyingAddition = Some(s.lisaQualifyingAddition),
          lisaBonusClaim = Some(s.lisaBonusClaim),
          closureDate = Some(s.closureDate),
          lisaClosureReason = Some(s.reasonForClosure)
        )
    }
  }
}
