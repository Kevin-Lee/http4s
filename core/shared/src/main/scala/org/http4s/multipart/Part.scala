/*
 * Copyright 2013 http4s.org
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

package org.http4s
package multipart

import cats.effect.Sync
import fs2.Stream
import fs2.io.file.Files
import fs2.io.file.Flags
import fs2.io.file.Path
import fs2.io.readInputStream
import fs2.text.utf8
import org.http4s.headers.`Content-Disposition`
import org.http4s.headers.`Content-Transfer-Encoding`
import org.typelevel.ci._

import java.io.File
import java.io.InputStream
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.charset.{Charset => NioCharset}

final case class Part[F[_]](headers: Headers, body: Stream[F, Byte]) extends Media[F] {

  /** This part's name from its Content-Disposition header, decoded as
    * UTF-8.
    */
  def name: Option[String] =
    name(StandardCharsets.UTF_8)

  /** This part's name from its Content-Disposition header, decoded as
    * the specified charset.
    */
  def name(charset: NioCharset): Option[String] =
    contentDispositionParam(ci"name", charset)

  /** This part's name from its Content-Disposition header, decoded from
    * raw bytes with the specified function.
    */
  def nameDecoded(f: Array[Byte] => Option[String]): Option[String] =
    contentDispositionParamDecoded(ci"name", f)

  /** This part's filename from its Content-Disposition header, decoded
    * as UTF-8.
    */
  def filename: Option[String] =
    filename(StandardCharsets.UTF_8)

  /** This part's filename from its Content-Disposition header, decoded
    * as the specified charset.
    */
  def filename(charset: NioCharset): Option[String] =
    contentDispositionParam(ci"filename", charset)

  /** This part's filename from its Content-Disposition header, decoded
    * from raw bytes with the specified function.
    */
  def filenameDecoded(f: Array[Byte] => Option[String]): Option[String] =
    contentDispositionParamDecoded(ci"filename", f)

  private def contentDispositionParam(name: CIString, charset: NioCharset): Option[String] =
    charset match {
      case StandardCharsets.ISO_8859_1 =>
        headers.get[`Content-Disposition`].flatMap(_.parameters.get(name))
      case charset =>
        contentDispositionParamDecoded(name, bytes => Some(new String(bytes, charset)))
    }

  private def contentDispositionParamDecoded(
      name: CIString,
      f: Array[Byte] => Option[String],
  ): Option[String] =
    headers
      .get[`Content-Disposition`]
      .flatMap(_.parameters.get(name).map(_.getBytes(StandardCharsets.ISO_8859_1)).flatMap(f))

  override def covary[F2[x] >: F[x]]: Part[F2] = this.asInstanceOf[Part[F2]]
}

object Part {
  private val ChunkSize = 8192

  def formData[F[_]](name: String, value: String, headers: Header.ToRaw*): Part[F] =
    Part(
      Headers(`Content-Disposition`("form-data", Map(ci"name" -> name))).put(headers: _*),
      Stream.emit(value).through(utf8.encode),
    )

  @deprecated("Use overload with fs2.io.file.Path", "0.23.5")
  def fileData[F[_]: Files](name: String, file: File, headers: Header.ToRaw*): Part[F] =
    fileData(name, Path.fromNioPath(file.toPath), headers: _*)

  def fileData[F[_]: Files](name: String, path: Path, headers: Header.ToRaw*): Part[F] =
    fileData(
      name,
      path.fileName.toString,
      Files[F].readAll(path, ChunkSize, Flags.Read),
      headers: _*
    )

  def fileData[F[_]: Sync](name: String, resource: URL, headers: Header.ToRaw*): Part[F] =
    fileData(name, resource.getPath.split("/").last, resource.openStream(), headers: _*)

  def fileData[F[_]](
      name: String,
      filename: String,
      entityBody: EntityBody[F],
      headers: Header.ToRaw*
  ): Part[F] = {
    val binaryContentTransferEncoding: `Content-Transfer-Encoding` =
      `Content-Transfer-Encoding`.Binary
    Part(
      Headers(
        `Content-Disposition`("form-data", Map(ci"name" -> name, ci"filename" -> filename)),
        binaryContentTransferEncoding,
      ).put(headers: _*),
      entityBody,
    )
  }

  // The InputStream is passed by name, and we open it in the by-name
  // argument in callers, so we can avoid lifting into an effect.  Exposing
  // this API publicly would invite unsafe use, and the `EntityBody` version
  // should be safe.
  private def fileData[F[_]](
      name: String,
      filename: String,
      in: => InputStream,
      headers: Header.ToRaw*
  )(implicit F: Sync[F]): Part[F] =
    fileData(name, filename, readInputStream(F.delay(in), ChunkSize), headers: _*)
}
