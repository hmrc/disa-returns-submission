package uk.gov.hmrc.disareturnssubmission.utils

import uk.gov.hmrc.objectstore.client.Md5Hash

import java.nio.file.{Files, Path as FilePath}
import java.security.MessageDigest
import java.util.Base64
import scala.util.Using

trait Md5Base64 {
  def checkMd5Base64(file: FilePath): Md5Hash = {
    val digest = MessageDigest.getInstance("MD5")
    val buffer = new Array[Byte](8192)

    Using.resource(Files.newInputStream(file)) { input =>
      var read = input.read(buffer)

      while (read != -1) {
        digest.update(buffer, 0, read)
        read = input.read(buffer)
      }
    }

    Md5Hash(Base64.getEncoder.encodeToString(digest.digest()))
  }
}
