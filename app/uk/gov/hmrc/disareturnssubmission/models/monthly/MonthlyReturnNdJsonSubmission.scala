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

import play.api.libs.functional.syntax.*
import play.api.libs.json.*
import uk.gov.hmrc.disareturnssubmission.models.enums.*

import java.time.LocalDate

final case class MonthlyReturnNdJsonSubmissionCommon(
  accountNumber: String,
  nino: String,
  firstName: String,
  middleName: Option[String],
  lastName: String,
  dateOfBirth: LocalDate,
  amountTransferredIn: BigDecimal,
  amountTransferredOut: BigDecimal,
  dateOfLastSubscription: LocalDate,
  totalCurrentYearSubscriptionsToDate: BigDecimal,
  marketValueOfAccount: BigDecimal,
  isaType: IsaType
)

sealed trait MonthlyReturnNdJsonSubmission {
  def common: MonthlyReturnNdJsonSubmissionCommon
}

final case class MonthlyReturnNdJsonStandardSubscription(
  common: MonthlyReturnNdJsonSubmissionCommon,
  flexibleIsa: Boolean
) extends MonthlyReturnNdJsonSubmission

final case class MonthlyReturnNdJsonStandardClosure(
  common: MonthlyReturnNdJsonSubmissionCommon,
  flexibleIsa: Boolean,
  closureDate: LocalDate,
  reasonForClosure: LisaClosureReason
) extends MonthlyReturnNdJsonSubmission

final case class MonthlyReturnNdJsonLifetimeSubscription(
  common: MonthlyReturnNdJsonSubmissionCommon,
  dateOfFirstSubscription: LocalDate,
  lisaQualifyingAddition: BigDecimal,
  lisaBonusClaim: BigDecimal
) extends MonthlyReturnNdJsonSubmission

final case class MonthlyReturnNdJsonLifetimeClosure(
  common: MonthlyReturnNdJsonSubmissionCommon,
  dateOfFirstSubscription: LocalDate,
  lisaQualifyingAddition: BigDecimal,
  lisaBonusClaim: BigDecimal,
  closureDate: LocalDate,
  reasonForClosure: LisaClosureReason
) extends MonthlyReturnNdJsonSubmission

object MonthlyReturnNdJsonSubmission {
  private def enumFormat[A](values: Array[A]): Format[A] =
    Format(
      Reads.StringReads.flatMapResult { value =>
        values.find(_.toString == value) match {
          case Some(enumValue) => JsSuccess(enumValue)
          case None            => JsError("Unsupported enum value")
        }
      },
      Writes(value => JsString(value.toString))
    )

  private implicit val isaTypeFormat: Format[IsaType]                             = enumFormat(IsaType.values)
  private implicit val closureReasonFormat: Format[LisaClosureReason]             =
    enumFormat(LisaClosureReason.values)
  private implicit val decimalReads: Reads[BigDecimal]                            = Reads {
    case JsNumber(value) => JsSuccess(value)
    case _               => JsError("Expected a JSON number")
  }
  private val commonReads: Reads[MonthlyReturnNdJsonSubmissionCommon]             = Json.reads[MonthlyReturnNdJsonSubmissionCommon]
  private implicit val commonWrites: OWrites[MonthlyReturnNdJsonSubmissionCommon] =
    Json.writes[MonthlyReturnNdJsonSubmissionCommon]

  private val standardSubscriptionReads: Reads[MonthlyReturnNdJsonStandardSubscription] = (
    commonReads and (__ \ "flexibleIsa").read[Boolean]
  )(MonthlyReturnNdJsonStandardSubscription.apply)

  private val standardClosureReads: Reads[MonthlyReturnNdJsonStandardClosure] = (
    commonReads and
      (__ \ "flexibleIsa").read[Boolean] and
      (__ \ "closureDate").read[LocalDate] and
      (__ \ "reasonForClosure").read[LisaClosureReason]
  )(MonthlyReturnNdJsonStandardClosure.apply)

  private val lifetimeSubscriptionReads: Reads[MonthlyReturnNdJsonLifetimeSubscription] = (
    commonReads and
      (__ \ "dateOfFirstSubscription").read[LocalDate] and
      (__ \ "lisaQualifyingAddition").read[BigDecimal] and
      (__ \ "lisaBonusClaim").read[BigDecimal]
  )(MonthlyReturnNdJsonLifetimeSubscription.apply)

  private val lifetimeClosureReads: Reads[MonthlyReturnNdJsonLifetimeClosure] = (
    commonReads and
      (__ \ "dateOfFirstSubscription").read[LocalDate] and
      (__ \ "lisaQualifyingAddition").read[BigDecimal] and
      (__ \ "lisaBonusClaim").read[BigDecimal] and
      (__ \ "closureDate").read[LocalDate] and
      (__ \ "reasonForClosure").read[LisaClosureReason]
  )(MonthlyReturnNdJsonLifetimeClosure.apply)

  implicit val reads: Reads[MonthlyReturnNdJsonSubmission] = Reads { json =>
    (json \ "isaType").validate[IsaType].flatMap { isaType =>
      val isClosure = (json \ "closureDate").toOption.nonEmpty || (json \ "reasonForClosure").toOption.nonEmpty
      (isaType == IsaType.LIFETIME, isClosure) match {
        case (false, false) => standardSubscriptionReads.reads(json)
        case (false, true)  => standardClosureReads.reads(json)
        case (true, false)  => lifetimeSubscriptionReads.reads(json)
        case (true, true)   => lifetimeClosureReads.reads(json)
      }
    }
  }

  implicit val writes: Writes[MonthlyReturnNdJsonSubmission] = Writes { submission =>
    val common   = Json.toJson(submission.common).as[JsObject]
    val specific = submission match {
      case value: MonthlyReturnNdJsonStandardSubscription =>
        Json.obj("flexibleIsa" -> value.flexibleIsa)
      case value: MonthlyReturnNdJsonStandardClosure      =>
        Json.obj(
          "flexibleIsa"      -> value.flexibleIsa,
          "closureDate"      -> value.closureDate,
          "reasonForClosure" -> value.reasonForClosure
        )
      case value: MonthlyReturnNdJsonLifetimeSubscription =>
        Json.obj(
          "dateOfFirstSubscription" -> value.dateOfFirstSubscription,
          "lisaQualifyingAddition"  -> value.lisaQualifyingAddition,
          "lisaBonusClaim"          -> value.lisaBonusClaim
        )
      case value: MonthlyReturnNdJsonLifetimeClosure      =>
        Json.obj(
          "dateOfFirstSubscription" -> value.dateOfFirstSubscription,
          "lisaQualifyingAddition"  -> value.lisaQualifyingAddition,
          "lisaBonusClaim"          -> value.lisaBonusClaim,
          "closureDate"             -> value.closureDate,
          "reasonForClosure"        -> value.reasonForClosure
        )
    }
    common ++ specific
  }
}
