/**
 * Copyright (C) 2015 DANS - Data Archiving and Networked Services (info@dans.knaw.nl)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nl.knaw.dans.easy.sword2.properties

import java.nio.file.attribute.FileTime
import java.nio.file.{ Files, Path }

import nl.knaw.dans.easy.sword2.State.{ DRAFT, State }
import nl.knaw.dans.easy.sword2.properties.DepositPropertiesFile._
import nl.knaw.dans.easy.sword2.{ DepositId, FileOps, Settings, State, dateTimeFormatter }
import nl.knaw.dans.lib.error._
import nl.knaw.dans.lib.logging.DebugEnhancedLogging
import org.apache.commons.configuration.PropertiesConfiguration
import org.apache.commons.lang.StringUtils
import org.joda.time.{ DateTime, DateTimeZone }

import scala.util.{ Failure, Success, Try }

/**
 * Loads the current `deposit.properties` for the specified deposit. This class is not thread-safe, so it is assumed
 * that it is used from one processing thread only (at least per deposit). It looks for the deposit first in the
 * temporary download directory, and if not found there, in the ingest-flow inbox.
 *
 * @param properties the deposit's properties
 */
class DepositPropertiesFile(properties: PropertiesConfiguration) extends DepositProperties with DebugEnhancedLogging {

  debug(s"Using deposit.properties at ${ properties.getFile }")

  /**
   * Saves the deposit file to disk.
   *
   * @return
   */
  override def save(): Try[Unit] = Try {
    debug("Saving deposit.properties")
    properties.save()
  }

  override def exists: Boolean = properties.getFile.exists

  def getDepositId: DepositId = {
    properties.getFile.getParentFile.getName
  }

  override def setState(state: State, descr: String): Try[DepositProperties] = Try {
    properties.setProperty("state.label", state)
    properties.setProperty("state.description", descr)
    this
  }

  /**
   * Returns the state when the properties were loaded.
   *
   * @return
   */
  override def getState: Try[(State, String)] = {
    for {
      label <- Try { Option(properties.getString("state.label")).map(State.withName) }
      descr <- Try { Option(properties.getString("state.description")) }
      result <- label.flatMap(state => descr.map((state, _)))
        .map(Success(_))
        .getOrElse(Failure(new IllegalStateException("Deposit without state")))
    } yield result
  }

  override def setBagName(bagName: String): Try[DepositProperties] = Try {
    properties.setProperty("bag-store.bag-name", bagName)
    this
  }

  override def setClientMessageContentType(contentType: String): Try[DepositProperties] = Try {
    properties.setProperty(CLIENT_MESSAGE_CONTENT_TYPE_KEY, contentType)
    this
  }

  override def removeClientMessageContentType(): Try[DepositProperties] = Try {
    properties.clearProperty(CLIENT_MESSAGE_CONTENT_TYPE_KEY)
    properties.clearProperty(CLIENT_MESSAGE_CONTENT_TYPE_KEY_OLD) // Also clean up old contentType property if still found
    this
  }

  override def getClientMessageContentType: Try[String] = {
    Seq(properties.getString(CLIENT_MESSAGE_CONTENT_TYPE_KEY),
      properties.getString(CLIENT_MESSAGE_CONTENT_TYPE_KEY_OLD)) // Also look for old contentType to support pre-upgrade deposits
      .find(StringUtils.isNotBlank)
      .map(Success(_))
      .getOrElse(Failure(new IllegalStateException(s"Deposit without $CLIENT_MESSAGE_CONTENT_TYPE_KEY")))
  }

  override def getDepositorId: Try[String] = {
    Option(properties.getString("depositor.userId"))
      .map(Success(_))
      .getOrElse(Failure(new IllegalStateException("Deposit without depositor")))
  }

  override def getDoi: Try[Option[String]] = Try {
    Option(properties.getString("identifier.doi"))
  }

  /**
   * Returns the last modified timestamp when the properties were loaded.
   *
   * @return
   */
  override def getLastModifiedTimestamp: Try[Option[FileTime]] = Try {
    Option(properties.getFile.toPath)
      .filter(Files.exists(_))
      .map(Files.getLastModifiedTime(_))
  }
}

object DepositPropertiesFile extends DepositPropertiesFactory {
  val FILENAME = "deposit.properties"
  private val CLIENT_MESSAGE_CONTENT_TYPE_KEY_OLD = "contentType" // for backwards compatibility
  private val CLIENT_MESSAGE_CONTENT_TYPE_KEY = "easy-sword2.client-message.content-type"

  private def fileLocation(depositId: DepositId)(implicit settings: Settings): Path = {
    (settings.tempDir #:: settings.depositRootDir #:: settings.archivedDepositRootDir.toStream)
      .map(_.toPath.resolve(depositId))
      .collectFirst { case path if Files.exists(path) => path.resolve(FILENAME) }
      .getOrElse { settings.tempDir.toPath.resolve(depositId).resolve(FILENAME) }
  }

  private def from(depositId: DepositId)(fillProps: (PropertiesConfiguration, Path) => Unit)(implicit settings: Settings): Try[DepositPropertiesFile] = Try {
    val file = fileLocation(depositId)
    val props = new PropertiesConfiguration() {
      setDelimiterParsingDisabled(true)
      setFile(file.toFile)
    }
    fillProps(props, file)

    new DepositPropertiesFile(props)
  }

  override def load(depositId: DepositId)(implicit settings: Settings): Try[DepositProperties] = {
    from(depositId) {
      case (props, file) if Files.exists(file) => props.load(file.toFile)
      case (_, file) => throw new Exception(s"deposit $file does not exist")
    }
  }

  override def create(depositId: DepositId, depositorId: String)(implicit settings: Settings): Try[DepositProperties] = {
    for {
      props <- from(depositId) {
        case (_, file) if Files.exists(file) => throw new Exception(s"deposit $file already exists")
        case (props, _) =>
          props.setProperty("bag-store.bag-id", depositId)
          props.setProperty("creation.timestamp", DateTime.now(DateTimeZone.UTC).toString(dateTimeFormatter))
          props.setProperty("deposit.origin", "SWORD2")
          props.setProperty("state.label", DRAFT)
          props.setProperty("state.description", "Deposit is open for additional data")
          props.setProperty("depositor.userId", depositorId)
      }
      _ <- props.save()
    } yield props
  }

  override def getSword2UploadedDeposits(implicit settings: Settings): Try[Iterator[(DepositId, String)]] = {
    def depositHasState(props: DepositProperties): Boolean = {
      props.getState
        .map { case (label, _) => label }
        .doIfFailure { case _: Throwable => logger.warn(s"[${ props.getDepositId }] Could not get deposit state. Not putting this deposit on the queue.") }
        .fold(_ => false, _ == State.UPLOADED)
    }

    def getContentType(props: DepositProperties): Try[String] = {
      props.getClientMessageContentType
        .doIfFailure { case _: Throwable => logger.warn(s"[${ props.getDepositId }] Could not get deposit Content-Type. Not putting this deposit on the queue.") }
    }

    Try {
      settings.tempDir
        .listFilesSafe
        .toIterator
        .collect { case file if file.isDirectory => load(file.getName).unsafeGetOrThrow }
        .filter(depositHasState)
        .map(props => getContentType(props).map((props.getDepositId, _)).unsafeGetOrThrow)
    }
  }
}