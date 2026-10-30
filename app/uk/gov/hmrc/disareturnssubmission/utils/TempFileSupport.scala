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

package uk.gov.hmrc.disareturnssubmission.utils

import play.api.Logging
import play.api.libs.Files.*

import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.*
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

trait TempFileSupport extends Logging {

  protected def temporaryFileCreator: TemporaryFileCreator
  protected def blockingExecutionContext: ExecutionContext

  protected def withTempFile[A](prefix: String)(use: Path => Future[A])(implicit ec: ExecutionContext): Future[A] =
    withTempFiles(prefix) { create =>
      Future(blocking(create()))(blockingExecutionContext).flatMap(use)
    }

  protected def withTempFiles[A](
    prefix: String
  )(use: (() => Path) => Future[A])(implicit ec: ExecutionContext): Future[A] = {
    val files          = new ConcurrentLinkedQueue[TemporaryFile]()
    def create(): Path = {
      val file = temporaryFileCreator.create(prefix)
      files.add(file)
      file.path
    }

    Future.unit.flatMap(_ => use(() => create())).transformWith { result =>
      Future {
        blocking {
          files.asScala.foreach { file =>
            try file.delete()
            catch {
              case NonFatal(exception) =>
                logger.warn(s"[TempFileSupport][withTempFiles] Failed to delete temporary file [$file]", exception)
            }
          }
        }
      }(blockingExecutionContext).transform(_ => result)
    }
  }
}
