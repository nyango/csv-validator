/**
 * Copyright (c) 2013, The National Archives <digitalpreservation@nationalarchives.gov.uk>
 * http://www.nationalarchives.gov.uk
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package uk.gov.nationalarchives.csv.validator.cmd


import java.text.DecimalFormat

import resource.managed
import scalax.file.Path
import scalaz.{Success => SuccessZ, Failure => FailureZ, _}
import scopt.Read
import uk.gov.nationalarchives.csv.validator._
import uk.gov.nationalarchives.csv.validator.api.{CsvValidator, TextFile}
import uk.gov.nationalarchives.csv.validator.api.CsvValidator.{SubstitutePath, createValidator}
import java.net.URL
import java.nio.charset.Charset
import java.util.jar.{Attributes, Manifest}

object SystemExitCodes extends Enumeration {
  type ExitCode = Int
  sealed abstract class SystemExitCode(val code: ExitCode)

  case object ValidCsv extends SystemExitCode(0)
  case object IncorrectArguments extends SystemExitCode(1)
  case object InvalidSchema extends SystemExitCode(2)
  case object InvalidCsv extends SystemExitCode(3)
}

object CsvValidatorCmdApp extends App {

  type ExitMessage = String
  type ExitStatus = (ExitMessage, SystemExitCodes.SystemExitCode)

  val (exitMessage, systemExitCode) = run(args)
  println(exitMessage)
  System.exit(systemExitCode.code)

  case class Config(traceParser: Boolean = false,
                    failFast: Boolean = false,
                    substitutePaths: List[SubstitutePath] = List.empty[SubstitutePath],
                    caseSensitivePaths: Boolean = false,
                    showVersion: Boolean = false,
                    csvPath: Path = Path.fromString("."),
                    csvEncoding: Charset = CsvValidator.DEFAULT_ENCODING,
                    csvSchemaPath: Path = Path.fromString("."),
                    csvSchemaEncoding: Charset = CsvValidator.DEFAULT_ENCODING,
                    disableUtf8Validation:Boolean = false,
                    progressCallback: Option[ProgressCallback] = None)

  def run(args: Array[String]): ExitStatus = {

    implicit val pathRead: Read[Path] = Read.reads { Path.fromString(_) }
    implicit val charsetRead: Read[Charset] = Read.reads { Charset.forName(_) }

    val parser = new scopt.OptionParser[Config]("validate") {
        head("CSV Validator - Command Line", getShortVersion())
        help("help") text("Prints this usage text")
        //opt[Boolean]("version") action { (x,c) => c.copy(showVersion = x) } text { "Display the version information" } //TODO would be nice if '--version' could be used without specifying CSV+CSVS paths etc //getLongVersion().map(x => s"${x._1}: ${x._2}").foreach(println(_))
        opt[Unit]('t', "trace-parser") optional() action { (_, c) => c.copy(traceParser = true)} text("Prints a trace of the parser parse")
        opt[Boolean]('f', "fail-fast") optional() action { (x,c) => c.copy(failFast = x) } text("Stops on the first validation error rather than reporting all errors")
        opt[SubstitutePath]('p', "path") optional() unbounded() action { (x,c) => c.copy(substitutePaths = c.substitutePaths :+ x) } text("Allows you to substitute a file path (or part of) in the CSV for a different file path")
        opt[Boolean]('c', "case-sensitive-paths") optional() action { (x,c) => c.copy(caseSensitivePaths = x) } text("Enforces case-sensitive file path checking. Useful when validating on case-insensitive filesystems like Windows NTFS")
        opt[Charset]('x', "csv-encoding") optional() action { (x,c) => c.copy(csvEncoding = x) } text("Defines the charset encoding used in the CSV file")
        opt[Charset]('y', "csv-schema-encoding") optional() action { (x,c) => c.copy(csvSchemaEncoding = x) } text("Defines the charset encoding used in the CSV Schema file")
        opt[Unit]("disable-utf8-validation") optional() action {(_, c) => c.copy(disableUtf8Validation = true)} text("Disable UTF-8 validation for CSV files.")
        opt[Unit]("show-progress") optional() action {(_, c) => c.copy(progressCallback = Some(commandLineProgressCallback))} text("Show progress")
        arg[Path]("<csv-path>") validate { x => if(x.exists && x.canRead) success else failure(s"Cannot access CSV file: ${x.path}") } action { (x,c) => c.copy(csvPath = x) } text("The path to the CSV file to validate")
        arg[Path]("<csv-schema-path>") validate { x => if(x.exists && x.canRead) success else failure(s"Cannot access CSV Schema file: ${x.path}") } action { (x,c) => c.copy(csvSchemaPath = x) } text("The path to the CSV Schema file to use for validation")
    }

    //parse the command line arguments
    parser.parse(args, new Config()) map {
      config =>
        validate(TextFile(config.csvPath, config.csvEncoding, !config.disableUtf8Validation), TextFile(config.csvSchemaPath, config.csvSchemaEncoding), config.failFast, config.substitutePaths, config.caseSensitivePaths, config.traceParser, config.progressCallback)
    } getOrElse {
      //arguments are bad, usage message will have been displayed
      ("", SystemExitCodes.IncorrectArguments)
    }
  }

  def commandLineProgressCallback() =  new ProgressCallback {

    private val numberFormat = new DecimalFormat("0% \n")

    override def update(complete: Percentage): Unit = Console.out.println(numberFormat.format(complete/100))

    override def update(total: Int, processed: Int): Unit = Console.out.println(s"processing ${processed} of ${total}")
  }

  private def getShortVersion(): String = {
    extractFromManifest {
      attributes =>
        attributes.getValue("Implementation-Version")
    }.getOrElse("UNKNOWN")
  }

  private def getLongVersion(): Seq[(String, String)] = {
    extractFromManifest {
      attributes =>
        Seq(
          ("Version", attributes.getValue("Implementation-Version")),
          ("Revision", attributes.getValue("Git-Commit")),
          ("Build Timestamp", attributes.getValue("Build-Timestamp"))
        )
    }.getOrElse(Seq(("Version", "UNKNOWN")))
  }

  private def extractFromManifest[T](extractor: Attributes => T): Option[T] = {
    val clazz = getClass()
    val className = clazz.getSimpleName + ".class"
    val classPath = clazz.getResource(className).toString()
    if (!classPath.startsWith("jar")) {
      None // Class not from JAR
    } else {
      val manifestPath = classPath.substring(0, classPath.lastIndexOf("!") + 1) + "/META-INF/MANIFEST.MF"
      managed(new URL(manifestPath).openStream()).map {
        is =>
          val manifest = new Manifest(is)
          extractor(manifest.getMainAttributes)
      }.opt
    }
  }

  def validate(csvFile: TextFile, schemaFile: TextFile, failFast: Boolean, pathSubstitutionsList: List[SubstitutePath], enforceCaseSensitivePathChecks: Boolean, trace: Boolean, progress: Option[ProgressCallback]): ExitStatus = {
    val validator = createValidator(failFast, pathSubstitutionsList, enforceCaseSensitivePathChecks, trace)
    validator.parseSchema(schemaFile) match {
      case FailureZ(errors) => (prettyPrint(errors), SystemExitCodes.InvalidSchema)
      case SuccessZ(schema) =>
        validator.validate(csvFile, schema, progress) match {
          case FailureZ(failures) =>
            val failuresMsg = prettyPrint(failures)
            if(containsError(failures))  //checks for just warnings to determine exit code
              (failuresMsg + EOL + "FAIL",
                SystemExitCodes.InvalidCsv)
            else
              (failuresMsg + EOL + "PASS", //just warnings!
                SystemExitCodes.ValidCsv)

          case SuccessZ(_) => ("PASS", SystemExitCodes.ValidCsv)
        }
    }
  }

  private def containsError(l: NonEmptyList[FailMessage]) : Boolean = {
    l.list.find(_ match {
      case ErrorMessage(_, _, _) => true
      case _ => false
    }).nonEmpty
  }

  private def prettyPrint(l: NonEmptyList[FailMessage]): String = l.list.map { i =>
    i match {
      case WarningMessage(err,_,_) => "Warning: " + err
      case ErrorMessage(err,_,_) =>   "Error:   " + err
      case SchemaMessage(err,_,_) =>  err
    }
  }.mkString(sys.props("line.separator"))
}