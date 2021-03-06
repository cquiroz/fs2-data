/*
 * Copyright 2019 Lucas Satabin
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
package fs2.data.csv

import cats._
import cats.implicits._
import cats.data.NonEmptyList

import scala.annotation.{implicitNotFound, tailrec}

/** Describes how a row can be decoded to the given type.
  *
  * `RowDecoder` provides convenient methods such as `map`, `emap`, or `flatMap`
  * to build new decoders out of more basic one.
  *
  * Actually, `RowDecoder` has a [[https://typelevel.org/cats/api/cats/MonadError.html cats `MonadError`]]
  * instance. To get the full power of it, import `cats.implicits._`.
  *
  */
@implicitNotFound(
  "No implicit RowDecoder found for type ${T}.\nYou can define one using RowDecoder.instance, by calling map on another RowDecoder or by using generic derivation for product types like case classes.\nFor that, add the fs2-data-csv-generic module to your dependencies and use either full-automatic derivation:\nimport fs2.data.csv.generic.auto._\nor the recommended semi-automatic derivation:\nimport fs2.data.csv.generic.semiauto._\nimplicit val rowDecoder: RowDecoder[${T}] = deriveRowDecoder\nMake sure to have instances of CellDecoder for every member type in scope.\n")
trait RowDecoder[T] {
  def apply(row: NonEmptyList[String]): DecoderResult[T]

  /**
    * Map the parsed value.
    * @param f the mapping function
    * @tparam T2 the result type
    * @return a row decoder reading the mapped type
    */
  def map[T2](f: T => T2): RowDecoder[T2] =
    row => apply(row).map(f)

  /**
    * Map the parsed value to a new decoder, which in turn will be applied to
    * the parsed value.
    * @param f the mapping function
    * @tparam T2 the result type
    * @return a row decoder reading the mapped type
    */
  def flatMap[T2](f: T => RowDecoder[T2]): RowDecoder[T2] =
    row => apply(row).flatMap(f(_)(row))

  /**
    * Map the parsed value, potentially failing.
    * @param f the mapping function
    * @tparam T2 the result type
    * @return a row decoder reading the mapped type
    */
  def emap[T2](f: T => DecoderResult[T2]): RowDecoder[T2] =
    row => apply(row).flatMap(f)

  /**
    * Fail-over. If this decoder fails, try the supplied other decoder.
    * @param cd the fail-over decoder
    * @tparam TT the return type
    * @return a decoder combining this and the other decoder
    */
  def or[TT >: T](cd: => RowDecoder[TT]): RowDecoder[TT] =
    row =>
      apply(row) match {
        case Left(_)      => cd(row)
        case r @ Right(_) => r.leftCast[DecoderError]
      }

  /**
    * Similar to [[or]], but return the result as an Either signaling which row decoder succeeded. Allows for parsing
    * an unrelated type in case of failure.
    * @param cd the alternative decoder
    * @tparam B the type the alternative decoder returns
    * @return a decoder combining both decoders
    */
  def either[B](cd: RowDecoder[B]): RowDecoder[Either[T, B]] =
    row =>
      apply(row) match {
        case Left(_) =>
          cd(row) match {
            case l @ Left(_)  => l.rightCast[Either[T, B]]
            case r @ Right(_) => r.leftCast[T].asRight
          }
        case Right(value) => Right(Left(value))
      }

}

object RowDecoder extends ExportedRowDecoders {

  implicit object RowDecoderMonadError extends MonadError[RowDecoder, DecoderError] with SemigroupK[RowDecoder] {
    override def map[A, B](fa: RowDecoder[A])(f: A => B): RowDecoder[B] =
      fa.map(f)

    def flatMap[A, B](fa: RowDecoder[A])(f: A => RowDecoder[B]): RowDecoder[B] =
      fa.flatMap(f)

    def handleErrorWith[A](fa: RowDecoder[A])(f: DecoderError => RowDecoder[A]): RowDecoder[A] =
      row => fa(row).leftFlatMap(f(_)(row))

    def pure[A](x: A): RowDecoder[A] =
      _ => Right(x)

    def raiseError[A](e: DecoderError): RowDecoder[A] =
      _ => Left(e)

    def tailRecM[A, B](a: A)(f: A => RowDecoder[Either[A, B]]): RowDecoder[B] = {
      @tailrec
      def step(row: NonEmptyList[String], a: A): DecoderResult[B] =
        f(a)(row) match {
          case left @ Left(_)          => left.rightCast[B]
          case Right(Left(a))          => step(row, a)
          case Right(right @ Right(_)) => right.leftCast[DecoderError]
        }
      row => step(row, a)
    }

    def combineK[A](x: RowDecoder[A], y: RowDecoder[A]): RowDecoder[A] = x or y
  }

  @inline
  def apply[T: RowDecoder]: RowDecoder[T] = implicitly[RowDecoder[T]]

  @inline
  def instance[T](f: NonEmptyList[String] => DecoderResult[T]): RowDecoder[T] = f(_)
}

trait ExportedRowDecoders {
  implicit def exportedRowDecoders[A](implicit exported: Exported[RowDecoder[A]]): RowDecoder[A] = exported.instance
}
