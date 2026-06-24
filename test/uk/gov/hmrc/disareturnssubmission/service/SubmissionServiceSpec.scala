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

package uk.gov.hmrc.disareturnssubmission.service

import base.SpecBase
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import play.api.libs.Files.TemporaryFileCreator
import uk.gov.hmrc.disareturnssubmission.connectors.ObjectStoreConnector
import uk.gov.hmrc.disareturnssubmission.services.ObjectStoreService

import scala.concurrent.Future

class SubmissionServiceSpec extends SpecBase {
  val mockTemporaryFileCreator: TemporaryFileCreator = mock[TemporaryFileCreator]
  val mockObjectStoreConnector: ObjectStoreConnector = mock[ObjectStoreConnector]

  val service = new ObjectStoreService(mockObjectStoreConnector)

  "uploadFileToObjectStore should return the file location" in {
    when(mockObjectStoreConnector.putFile(any(), any(), any(), any())(any()))
      .thenReturn(Future.successful("test/test"))

    val res = service.uploadFileToObjectStore(testUploadReference, testTempFile, "application/x-ndjson", testMd5Hash)

    assert(res.futureValue === "test/test")
  }

}
