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
import play.api.Logger
import play.api.libs.Files.*
import play.api.test.Helpers.*
import uk.gov.hmrc.play.bootstrap.tools.LogCapturing

import java.io.File
import java.nio.file.Path
import scala.concurrent.*

class TempFileSupportSpec extends SpecBase with LogCapturing {
  private val expectedResult = 42
  private val tempFilePrefix = "temp-file-support"

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
      completed.success(expectedResult)
      await(result) mustBe expectedResult
      verify(file).delete()
    }

    "must clean up and return a failed Future when use throws" in new TestSetup {
      await(withFile[Unit](_ => throw useFailure).failed) mustBe useFailure

      verify(file).delete()
    }

    "must preserve a successful result when deletion throws" in new TestSetup {
      doThrow(deletionFailure).when(file).delete()

      withCaptureOfLoggingFrom(Logger(getClass)) { events =>
        await(withFile(_ => Future.successful(expectedResult))) mustBe expectedResult
        events.last.getMessage must startWith("[TempFileSupport][withTempFiles] Failed to delete temporary file [")
      }

      verify(file).delete()
    }

    "must preserve the original failure when deletion also throws" in new TestSetup {
      doThrow(deletionFailure).when(file).delete()

      await(withFile(_ => Future.failed[Unit](useFailure)).failed) mustBe useFailure

      verify(file).delete()
    }

    "must retain every created file until use completes and clean up before returning" in new TestSetup {
      val started   = Promise[Seq[Path]]()
      val completed = Promise[Int]()
      val result    = withFiles { create =>
        started.success(Seq(create(), create()))
        completed.future
      }

      await(started.future) mustBe Seq(path, secondPath)
      verify(file, never()).delete()
      verify(secondFile, never()).delete()
      completed.success(expectedResult)
      await(result) mustBe expectedResult
      verify(file).delete()
      verify(secondFile).delete()
    }

    Seq(true, false).foreach { synchronous =>
      s"must clean up every file when use fails ${if (synchronous) "synchronously" else "asynchronously"}" in new TestSetup {
        await(withFiles[Unit] { create =>
          create()
          create()
          if (synchronous) throw useFailure else Future.failed(useFailure)
        }.failed) mustBe useFailure

        verify(file).delete()
        verify(secondFile).delete()
      }
    }

    "must clean up files already created if a later creation fails" in new TestSetup {
      val failure = new IllegalStateException("creation failed")
      when(temporaryFileCreator.create(any[String], any[String])).thenReturn(temporaryFile).thenThrow(failure)

      await(withFiles[Unit] { create =>
        create()
        create()
        Future.unit
      }.failed) mustBe failure

      verify(file).delete()
      verify(secondFile, never()).delete()
    }

    Seq(true, false).foreach { successful =>
      s"must continue deleting later files and preserve a ${
          if (successful) "successful" else "failed"
        } result if deletion throws" in new TestSetup {
        doThrow(deletionFailure).when(file).delete()

        val result = withFiles { create =>
          create()
          create()
          if (successful) Future.successful(expectedResult) else Future.failed(useFailure)
        }

        if (successful) await(result) mustBe expectedResult else await(result.failed) mustBe useFailure
        verify(file).delete()
        verify(secondFile).delete()
      }
    }
  }

  private trait TestSetup extends TempFileSupport {
    val useFailure                                                    = new IllegalStateException("use failed")
    val deletionFailure                                               = new IllegalStateException("deletion failed")
    val file                                                          = mock[File]
    val temporaryFile                                                 = mock[TemporaryFile]
    val path                                                          = mock[Path]
    val secondFile                                                    = mock[File]
    val secondTemporaryFile                                           = mock[TemporaryFile]
    val secondPath                                                    = mock[Path]
    override protected val temporaryFileCreator: TemporaryFileCreator = mock[TemporaryFileCreator]
    override protected val blockingExecutionContext: ExecutionContext = ec

    when(temporaryFile.path).thenReturn(path)
    when(path.toFile).thenReturn(file)
    when(secondTemporaryFile.path).thenReturn(secondPath)
    when(secondPath.toFile).thenReturn(secondFile)
    when(temporaryFileCreator.create(any[String], any[String])).thenReturn(temporaryFile, secondTemporaryFile)

    def withFile[A](use: Path => Future[A]): Future[A]          = withTempFile(tempFilePrefix)(use)
    def withFiles[A](use: (() => Path) => Future[A]): Future[A] = withTempFiles(tempFilePrefix)(use)
  }
}
