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
package nl.knaw.dans.easy.sword2

import java.io.{ File, IOException }
import java.nio.file.attribute.{ BasicFileAttributes, PosixFilePermissions }
import java.nio.file.{ FileVisitResult, Files, Path, SimpleFileVisitor }

import nl.knaw.dans.easy.sword2.DepositHandler.{ isOnPosixFileSystem, log }
import nl.knaw.dans.lib.error._

import scala.util.Try

object FilesPermission {

  def changePermissionsRecursively(depositDir: File, permissions: String, id: DepositId): Try[Unit] = Try {
    if (isOnPosixFileSystem(depositDir))
      Files.walkFileTree(depositDir.toPath, ChangePermissionsRecursively(permissions, id))
  }

  case class ChangePermissionsRecursively(permissions: String,
                                          id: DepositId) extends SimpleFileVisitor[Path] {
    override def visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult = {
      log.debug(s"[$id] Setting the following permissions $permissions on file $path")
      Try {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions))
        FileVisitResult.CONTINUE
      } doIfFailure {
        case uoe: UnsupportedOperationException => log.error("Not on a POSIX supported file system", uoe)
        case cce: ClassCastException => log.error("No file permission elements in set", cce)
        case ioe: IOException => log.error(s"Could not set file permissions on $path", ioe)
        case se: SecurityException => log.error(s"Not enough privileges to set file permissions on $path", se)
        case e => log.error(s"Unexpected exception on $path", e)
      } getOrElse FileVisitResult.TERMINATE
    }

    override def postVisitDirectory(dir: Path, ex: IOException): FileVisitResult = {
      log.debug(s"[$id] Setting the following permissions $permissions on directory $dir")
      Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString(permissions))
      if (ex == null) FileVisitResult.CONTINUE
      else FileVisitResult.TERMINATE
    }
  }
}