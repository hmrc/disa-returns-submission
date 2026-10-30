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

import base.SpecBase
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import play.api.libs.Files.*
import play.api.test.Helpers.*

import java.io.File
import java.nio.file.Path
import scala.concurrent.*

class TempFileSupportSpec extends SpecBase {
  "TempFileSupport" - {
    "must retain the file until use completes and delete it before returning the result" in new TestSetup {
      val started   = Promise[Path]()
      val completed = Promise[Int]()

      val result = withFile { path =>
        started.success(path)
        completed.future
      }

      await(started.future) mustBe path
      result.isCompleted mustBe false
      verify(file, never()).delete()
      completed.success(42)
      await(result) mustBe 42
      verify(file).delete()
    }

    "must clean up and return a failed Future when use throws" in new TestSetup {
      val failure = new IllegalStateException("use failed")

      await(withFile[Unit](_ => throw failure).failed) mustBe failure

      verify(file).delete()
    }

    "must preserve a successful result when deletion throws" in new TestSetup {
      doThrow(new IllegalStateException("deletion failed")).when(file).delete()

      await(withFile(_ => Future.successful(42))) mustBe 42

      verify(file).delete()
    }

    "must preserve the original failure when deletion also throws" in new TestSetup {
      val failure = new IllegalStateException("use failed")
      doThrow(new IllegalStateException("deletion failed")).when(file).delete()

      await(withFile(_ => Future.failed[Unit](failure)).failed) mustBe failure

      verify(file).delete()
    }
  }

  private trait TestSetup extends TempFileSupport {
    val file                                                          = mock[File]
    val temporaryFile                                                 = mock[TemporaryFile]
    val path                                                          = mock[Path]
    override protected val temporaryFileCreator: TemporaryFileCreator = mock[TemporaryFileCreator]
    override protected val blockingExecutionContext: ExecutionContext = ec

    when(temporaryFile.path).thenReturn(path)
    when(path.toFile).thenReturn(file)
    when(temporaryFileCreator.create(any[String], any[String])).thenReturn(temporaryFile)

    def withFile[A](use: Path => Future[A]): Future[A] = withTempFile("temp-file-support")(use)
  }
}
