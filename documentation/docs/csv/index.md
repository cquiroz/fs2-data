---
title: CSV
description: CSV parser and codec
index: 0
module: csv
---

Module: [![Maven Central](https://img.shields.io/maven-central/v/org.gnieh/fs2-data-csv_2.13.svg)](https://mvnrepository.com/artifact/org.gnieh/fs2-data-csv_2.13)

The `fs2-data-csv` module contains tools to parse and transform CSV data. These tools live in the `fs2.data.csv` package.

This page covers the following topics:
* Contents
{:toc}

### Basic usage

The first one of the provided tools is the `rows` pipe, which transforms a stream of characters into a stream of parsed rows. The rows are represented by [`NonEmptyList[String]`][nel], empty lines are skipped. Values can be quoted and escaped, according to [the RFC][rfc].

```scala mdoc
import cats.effect._

import fs2._
import fs2.data.csv._

val input = """i,s,j
              |1,test,2
              |,other,-3
              |""".stripMargin

val stream = Stream.emits(input).through(rows[IO]())
stream.compile.toList.unsafeRunSync()
```

If your CSV data is not using the comma as separator, you can provide the character as parameter of the `rows` pipe. For instance, if your data uses semicolon as separator:

```scala mdoc
val input2 = """i;s;j
               |1;test;2""".stripMargin

val stream2 = Stream.emits(input2).through(rows[IO](';'))
stream2.compile.toList.unsafeRunSync()
```

### CSV rows with or without headers

Rows can be converted to a `Row` or `CsvRow[Header]` for some `Header` type. These classes provides higher-level utilities to manipulate rows.

If your CSV file doesn't have headers, you can use the `noHeaders` pipe, which creates `Row`.

```scala mdoc
val noh = stream.through(noHeaders[IO])
noh.map(_.values).compile.toList.unsafeRunSync()
```

If you want to consider the first row as a header row, you can use the `headers` pipe. For instance to have headers as `String`:

```scala mdoc
val withh = stream.through(headers[IO, String])
withh.map(_.toMap).compile.toList.unsafeRunSync()
```

To support your own type of `Header` you must provide an implicit `ParseableHeader[Header]`. For instance if you have a fix set of headers represented as [enumeratum][enumeratum] enum values, you can provide an instance of `ParseableHeader` as follows:

```scala mdoc
import enumeratum._
import cats.implicits._
import cats.data.NonEmptyList

sealed trait MyHeaders extends EnumEntry
object MyHeaders extends Enum[MyHeaders] {
  case object I extends MyHeaders
  case object S extends MyHeaders
  case object J extends MyHeaders
  def values = findValues
}

implicit object ParseableMyHeaders extends ParseableHeader[MyHeaders] {
  def apply(names: NonEmptyList[String]): HeaderResult[MyHeaders] =
    names.traverse { name =>
      MyHeaders.withNameInsensitiveOption(name) match {
        case Some(headers) => Right(headers)
        case None          => Left(new HeaderError(s"Unknown header $name"))
      }
    }
}

val withMyHeaders = stream.through(headers[IO, MyHeaders])
withMyHeaders.map(_.toMap).compile.toList.unsafeRunSync()
```

If the parse method fails for a header, the entire stream fails.

### Decoders

The library also provides a decoder interface, which allows for decoding CSV rows into arbitrary data types through the `decode` and `decodeRow` pipes. There are several kinds of decoders:
 - `CellDecoder` is a simple value decoder, used to transform values within the CSV fields. The library provides decoders for most of the Scala common types:
   - primitive types, strings
   - most of `java.time` types
   - if your wish to support custom field types, you need to implement your own `CellDecoder`
 - `RowDecoder` is used to decode CSV `Row`s through the `decode` pipe, and is positional. Use it when your CSV file doesn't have headers.
 - `CsvRowDecoder` is used to decode `CsvRow`s through the `decodeRow` pipe, and has access to the headers. Use it when you want to use header names to decode the rows.

For instance if you want to decode the CSV data into [shapeless][shapeless] `HList`:

```scala mdoc
import shapeless._

implicit object HListDecoder extends RowDecoder[Option[Int] :: String :: Int :: HNil] {
  def apply(cells: NonEmptyList[String]): DecoderResult[Option[Int] :: String :: Int :: HNil] =
    if(cells.size < 3)
      Left(new DecoderError("row is too short"))
    else
      for {
        i <- if(cells.head.isEmpty) Right(None) else CellDecoder[Int].apply(cells.head).map(Some(_))
        s <- CellDecoder[String].apply(cells.tail.head)
        j <- CellDecoder[Int].apply(cells.tail.tail.head)
      } yield i :: s :: j :: HNil
}

// .tail drops the header line
val hlists = noh.tail.through(decode[IO, Option[Int] :: String :: Int :: HNil])
hlists.compile.toList.unsafeRunSync()
```

Using the headers, one can decode the CSV data to some case class:

```scala mdoc
// note the order of fields is not the same as in the CSV data here
case class MyRow(i: Option[Int], j: Int, s: String)

implicit object MyRowDecoder extends CsvRowDecoder[MyRow, String] {
  def apply(row: CsvRow[String]): DecoderResult[MyRow] =
  for {
    i <- row.as[Int]("i").map(Some(_)).leftFlatMap(_ => Right(None))
    j <- row.as[Int]("j")
    s <- row.as[String]("s")
  } yield MyRow(i, j, s)
}
val decoded = withh.through(decodeRow[IO, String, MyRow])
decoded.compile.toList.unsafeRunSync()
```

As you can see this can be quite tedious to implement. Lucky us, the `fs2-data-csv-generic` module comes to the rescue to avoid having to write the boilerplate. Please refer to [the module documentation][csv-generic-doc] for more details.

[nel]: https://typelevel.org/cats/datatypes/nel.html
[rfc]: https://tools.ietf.org/html/rfc4180
[enumeratum]: https://github.com/lloydmeta/enumeratum/
[shapeless]: https://github.com/milessabin/shapeless
[csv-generic-doc]: /documentation/csv/generic/