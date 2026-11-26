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

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import play.api.libs.json.*
import uk.gov.hmrc.disareturnssubmission.models.enums.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.*
import java.time.LocalDate
import scala.jdk.CollectionConverters.*

class MonthlyReturnNpsSubmissionSpec extends AnyFreeSpec with Matchers {
  private val unknownEnumValue                                = "UNKNOWN"
  private val common                                          = MonthlyReturnNdJsonSubmissionCommon(
    accountNumber = "ACC123",
    nino = "AB123456C",
    firstName = "Ada",
    middleName = None,
    lastName = "Lovelace",
    dateOfBirth = LocalDate.parse("1980-01-02"),
    amountTransferredIn = BigDecimal(10),
    amountTransferredOut = BigDecimal(0),
    dateOfLastSubscription = LocalDate.parse("2026-05-01"),
    totalCurrentYearSubscriptionsToDate = BigDecimal(20),
    marketValueOfAccount = BigDecimal(100),
    isaType = IsaType.CASH
  )
  private val firstSubscriptionDate                           = LocalDate.parse("2026-04-07")
  private val closureDate                                     = LocalDate.parse("2026-05-02")
  private val lifetimeClosure                                 = MonthlyReturnNdJsonLifetimeClosure(
    common.copy(accountNumber = "ACC012", isaType = IsaType.LIFETIME),
    firstSubscriptionDate,
    BigDecimal(3),
    BigDecimal(-4),
    closureDate,
    LisaClosureReason.TRANSFERRED_IN_FULL
  )
  private val submissions: Seq[MonthlyReturnNdJsonSubmission] = Seq(
    MonthlyReturnNdJsonStandardSubscription(common, flexibleIsa = true),
    MonthlyReturnNdJsonStandardClosure(
      common.copy(
        accountNumber = "ACC456",
        middleName = Some("Byron"),
        isaType = IsaType.STOCKS_AND_SHARES
      ),
      flexibleIsa = false,
      closureDate,
      LisaClosureReason.CLOSED
    ),
    MonthlyReturnNdJsonLifetimeSubscription(
      common.copy(accountNumber = "ACC789", isaType = IsaType.LIFETIME),
      firstSubscriptionDate,
      BigDecimal(-3),
      BigDecimal(4)
    ),
    lifetimeClosure
  )
  private val backendRecords                                  = records("backend")

  "MonthlyReturnNpsSubmission" - {
    Seq("backend", "api").foreach { producer =>
      records(producer).zipWithIndex.foreach { (record, index) =>
        s"must read ${submissions(index).getClass.getSimpleName} from $producer and write the backend-compatible JSON" in {
          val submission = record.as[MonthlyReturnNdJsonSubmission]

          submission mustBe submissions(index)
          Json.toJson(submission) mustBe backendRecords(index)
          (Json.toJson(submission) \ "common").toOption mustBe None
        }
      }
    }

    Seq(
      IsaType.CASH,
      IsaType.STOCKS_AND_SHARES,
      IsaType.INNOVATIVE_FINANCE
    )
      .foreach { isaType =>
        s"must support the standard ISA type $isaType" in {
          val record = backendRecords.head + ("isaType" -> JsString(isaType.toString))
          record.as[MonthlyReturnNdJsonSubmission].common.isaType mustBe isaType
        }
      }

    LisaClosureReason.values.foreach { reason =>
      s"must support the lifetime closure reason $reason" in {
        val record = backendRecords.last + ("reasonForClosure" -> JsString(reason.toString))
        record.as[MonthlyReturnNdJsonSubmission] mustBe
          lifetimeClosure.copy(reasonForClosure = reason)
      }
    }

    backendRecords.head.keys.foreach { field =>
      s"must reject a standard subscription missing $field" in {
        (backendRecords.head - field).validate[MonthlyReturnNdJsonSubmission].isError mustBe true
      }
    }

    Seq("dateOfFirstSubscription", "lisaQualifyingAddition", "lisaBonusClaim").foreach { field =>
      s"must reject a lifetime subscription missing $field" in {
        (backendRecords(2) - field).validate[MonthlyReturnNdJsonSubmission].isError mustBe true
      }
    }

    Seq("closureDate", "reasonForClosure").foreach { field =>
      s"must reject an incomplete closure missing $field" in {
        (backendRecords(1) - field).validate[MonthlyReturnNdJsonSubmission].isError mustBe true
        (backendRecords.last - field).validate[MonthlyReturnNdJsonSubmission].isError mustBe true
      }
    }

    Seq(
      "isaType"             -> JsString(unknownEnumValue),
      "isaType"             -> JsNumber(1),
      "middleName"          -> JsNumber(1),
      "dateOfBirth"         -> JsString("1980-02-31"),
      "amountTransferredIn" -> JsString("10.00"),
      "flexibleIsa"         -> JsString("true")
    ).foreach { (field, value) =>
      s"must reject an invalid $field value $value" in {
        (backendRecords.head + (field -> value)).validate[MonthlyReturnNdJsonSubmission].isError mustBe true
      }
    }

    "must reject an unsupported closure reason" in {
      (backendRecords.last + ("reasonForClosure" -> JsString(unknownEnumValue)))
        .validate[MonthlyReturnNdJsonSubmission]
        .isError mustBe true
    }

    "must reject nested common fields instead of a flat record" in {
      Json.obj("common" -> backendRecords.head).validate[MonthlyReturnNdJsonSubmission].isError mustBe true
    }
  }

  private def records(producer: String): Seq[JsObject] =
    Files
      .readAllLines(Path.of(getClass.getResource(s"/monthly-return-submissions/$producer.ndjson").toURI), UTF_8)
      .asScala
      .toSeq
      .map(line => Json.parse(line).as[JsObject])
}
