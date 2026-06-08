package uk.gov.hmrc.disareturnssubmission.validators

import scala.util.matching.Regex

object TaxYearValidator {

  private val TaxYearPattern: Regex = raw"^20(\d{2})-(\d{2})$$".r

  def isValid(taxYear: String): Boolean =
    Option(taxYear).exists {
      case TaxYearPattern(startYear, endYear) =>
        endYear.toInt == startYear.toInt + 1
      case _                                  => false
    }
}
