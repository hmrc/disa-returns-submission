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

package uk.gov.hmrc.disareturnssubmission.models

import java.util.Locale

object ZReference {
  private val Pattern = "Z[0-9]{4}".r

  def normalize(value: String): Option[String] = {
    val normalized = value.trim.toUpperCase(Locale.ROOT)
    Option.when(Pattern.matches(normalized))(normalized)
  }
}
