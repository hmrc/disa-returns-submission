package uk.gov.hmrc.disareturnssubmission.utils

import java.util.UUID
import javax.inject.{Inject, Singleton}

@Singleton
class UuidGenerator @Inject() () {

  def randomUuid(): UUID =
    UUID.randomUUID()
}
