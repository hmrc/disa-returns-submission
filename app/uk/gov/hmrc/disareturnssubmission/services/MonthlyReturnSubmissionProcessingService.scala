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

package uk.gov.hmrc.disareturnssubmission.services

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.FileIO
import play.api.libs.Files.TemporaryFileCreator
import uk.gov.hmrc.disareturnssubmission.connectors.*
import uk.gov.hmrc.disareturnssubmission.converters.MonthlyReturnNdJsonToNpsSubmissionConverter
import uk.gov.hmrc.disareturnssubmission.models.monthly.*
import uk.gov.hmrc.disareturnssubmission.repositories.MonthlyReturnRepository
import uk.gov.hmrc.disareturnssubmission.utils.TempFileSupport
import uk.gov.hmrc.http.HeaderCarrier

import java.nio.file.Path
import javax.inject.*
import scala.concurrent.*
import scala.concurrent.duration.DurationLong
import scala.util.control.NonFatal

@Singleton
class MonthlyReturnSubmissionProcessingService @Inject() (
  actorSystem: ActorSystem,
  override protected val temporaryFileCreator: TemporaryFileCreator,
  converter: MonthlyReturnNdJsonToNpsSubmissionConverter,
  objectStoreConnector: ObjectStoreConnector,
  npsConnector: NpsConnector,
  repository: MonthlyReturnRepository
)(implicit ec: ExecutionContext, materializer: Materializer)
    extends TempFileSupport {

  override protected val blockingExecutionContext: ExecutionContext =
    actorSystem.dispatchers.lookup("contexts.monthly-return-submission-file-upload-blocking")

  def process(item: MonthlyReturnSubmissionWorkItem): Future[Unit] = {
    logger.info(s"[MonthlyReturnSubmissionProcessingService][process] Starting NPS transfer for ${context(item)}")
    repository.get(item.zReference, item.taxYear, item.month).flatMap { monthlyReturn =>
      monthlyReturn.flatMap(_.submissions.find(_.reference == item.reference)) match {
        case Some(submission) if submission.status == MonthlyReturnSubmissionStatus.NpsSubmitted =>
          logger.info(
            s"[MonthlyReturnSubmissionProcessingService][process] Submission already submitted to NPS; skipping transfer for ${context(item)}"
          )
          Future.unit
        case Some(submission)
            if submission.status == MonthlyReturnSubmissionStatus.Stored &&
              submission.submissionDetails.exists(_.objectStoreFileLocation.isDefined) =>
          transfer(item)
        case _                                                                                   =>
          val reason =
            if (monthlyReturn.isEmpty) "Monthly return not found"
            else "Submission missing or not ready for NPS transfer"
          logger.warn(
            s"[MonthlyReturnSubmissionProcessingService][process] $reason for ${context(item)}"
          )
          Future.failed(new IllegalStateException(s"Submission [${item.reference}] is not ready for NPS transfer"))
      }
    }
  }

  private def transfer(item: MonthlyReturnSubmissionWorkItem): Future[Unit] = {
    val startedAt = System.nanoTime()
    logger.debug(
      s"[MonthlyReturnSubmissionProcessingService][transfer] Downloading from Object Store for ${context(item)}"
    )
    withTempFile("monthly-return-submission-json") { output =>
      withTempFile("monthly-return-submission-ndjson") { input =>
        implicit val hc: HeaderCarrier = HeaderCarrier()
        for {
          source   <- objectStoreConnector.getFile(item.reference)
          download <- source.runWith(FileIO.toPath(input))
          _         =
            logger.debug(
              s"[MonthlyReturnSubmissionProcessingService][transfer] Downloaded [${download.count}] bytes; converting NDJSON to NPS JSON for ${context(item)}"
            )
          _        <- Future(blocking(converter.convert(input, output)))(blockingExecutionContext)
          _         = logger.debug(
                        s"[MonthlyReturnSubmissionProcessingService][transfer] Conversion completed for ${context(item)}"
                      )
        } yield ()
      }.flatMap(_ => sendSubmissionToNpsAndMarkSubmitted(item, output))
    }.map { _ =>
      logger.info(
        s"[MonthlyReturnSubmissionProcessingService][transfer] Submitted to NPS and marked [NPS_SUBMITTED] for ${context(item)} in [${(System.nanoTime() - startedAt).nanos.toMillis}] ms"
      )
    }
  }

  // shortcut: an accepted request can be resent after a crash; use NPS idempotency when its contract is available.
  private def sendSubmissionToNpsAndMarkSubmitted(item: MonthlyReturnSubmissionWorkItem, output: Path): Future[Unit] = {
    logger.debug(
      s"[MonthlyReturnSubmissionProcessingService][sendSubmissionToNpsAndMarkSubmitted] Sending to NPS for ${context(item)}"
    )
    for {
      _ <- npsConnector.sendSubmission(item.zReference, FileIO.fromPath(output))
      _  =
        logger.debug(
          s"[MonthlyReturnSubmissionProcessingService][sendSubmissionToNpsAndMarkSubmitted] NPS accepted submission; marking [NPS_SUBMITTED] for ${context(item)}"
        )
      _ <- repository
             .markSubmissionNpsSubmitted(item.zReference, item.taxYear, item.month, item.reference)
             .map { updated =>
               if (!updated) {
                 throw new IllegalStateException(s"Could not mark submission [${item.reference}] NPS_SUBMITTED")
               }
             }
             .recoverWith { case NonFatal(exception) =>
               logger.warn(
                 s"[MonthlyReturnSubmissionProcessingService][sendSubmissionToNpsAndMarkSubmitted] NPS accepted submission but could not mark [NPS_SUBMITTED] for ${context(item)}; transfer may be retried",
                 exception
               )
               Future.failed(exception)
             }
    } yield ()
  }

  private def context(item: MonthlyReturnSubmissionWorkItem): String =
    s"zReference [${item.zReference}], taxYear [${item.taxYear}], month [${item.month}], submission [${item.reference}]"
}
