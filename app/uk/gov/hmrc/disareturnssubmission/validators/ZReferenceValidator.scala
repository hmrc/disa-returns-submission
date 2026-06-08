package uk.gov.hmrc.disareturnssubmission.validators

import scala.util.matching.Regex

object ZReferenceValidator {

  private val ZReferencePattern: Regex = "^[zZ][0-9]{4}$".r

  def isValid(zReference: String): Boolean =
    Option(zReference).exists { value =>
      ZReferencePattern.pattern.matcher(value).matches()
    }
}
