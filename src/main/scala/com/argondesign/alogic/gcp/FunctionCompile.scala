////////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2017-2021 Argon Design Ltd. All rights reserved.
// This file is covered by the BSD (with attribution) license.
// See the LICENSE file for the precise wording of the license.
//
// DESCRIPTION:
// Google Cloud Function compiler endpoint
////////////////////////////////////////////////////////////////////////////////

package com.argondesign.alogic.gcp

import com.argondesign.alogic.BuildInfo
import com.argondesign.alogic.Compiler
import com.argondesign.alogic.core.MessageBuffer
import com.argondesign.alogic.core.Messages
import com.argondesign.alogic.CommandLineInterface
import com.argondesign.alogic.core.Loc
import com.argondesign.alogic.core.Messages.Error
import com.argondesign.alogic.core.Messages.Message
import com.google.cloud.functions.HttpFunction
import com.google.cloud.functions.HttpRequest
import com.google.cloud.functions.HttpResponse
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax._

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import java.util.stream.Collectors
import scala.annotation.nowarn
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._
import scala.util.chaining.scalaUtilChainingOps

class FunctionCompile extends HttpFunction {

  // API version reported to client
  private val apiVersion: Int = 1

  // Logger
  private val logger = Logger.getLogger(getClass.getName)

  // For timeout handling
  private val executorService = Executors.newSingleThreadExecutor()

  // Representation of a compiler Message in a response
  private case class ResponseMessage(
      file: String,
      line: Int,
      start: Int,
      end: Int,
      point: Int,
      category: String,
      lines: Seq[String],
      context: String)

  @nowarn("msg=never used") // 2.13: This actually is used...
  implicit private val responseMessageEncoder: io.circe.Encoder[ResponseMessage] =
    io.circe.generic.semiauto.deriveEncoder

  // Turns a compiler Message into a ResponseMessage
  private def toResponseMessage(m: Message, sandboxDirPrefix: String): ResponseMessage = {
    require(sandboxDirPrefix.endsWith(File.separator))
    val sandboxDirName = sandboxDirPrefix.dropRight(File.separator.length)
    ResponseMessage(
      if (m.loc eq Loc.unknown) "" else m.loc.file.stripPrefix(sandboxDirPrefix),
      m.loc.line,
      m.loc.start,
      m.loc.end,
      m.loc.point,
      m.category match {
        // $COVERAGE-OFF$ Trivial
        case Messages.WarningCategory => "WARNING"
        case Messages.ErrorCategory   => "ERROR"
        case Messages.NoteCategory    => "NOTE"
        case Messages.FatalCategory   => "FATAL"
        case Messages.IceCategory     => "INTERNAL COMPILER ERROR"
        // $COVERAGE-ON$
      },
      m.msg map { _.replaceAll(sandboxDirPrefix, "").replaceAll(sandboxDirName, ".") },
      m.context
    )
  }

  // Response to a 'describe' request
  private case class DescribeResult(
      apiVersion: Int,
      compilerVersion: String,
      buildTime: String)

  implicit private val describeResultEncoder: io.circe.Encoder[DescribeResult] =
    io.circe.generic.semiauto.deriveEncoder

  // Response to a 'compile' request
  private case class CompileResult(
      code: String,
      outputFiles: Map[String, String],
      messages: Seq[ResponseMessage])

  implicit private val compileResultEncoder: io.circe.Encoder[CompileResult] =
    io.circe.generic.semiauto.deriveEncoder

  // Create temporary directory, run 'f' passing the path, then delete it
  private def withTmpDir(f: Path => Unit): Unit = {
    def remove(f: File): Unit = {
      if (f.isDirectory) { f.listFiles foreach remove }
      f.delete()
    }
    val tmpPath = Files.createTempDirectory("compile").toFile.getCanonicalFile.toPath
    try {
      f(tmpPath)
    } finally {
      remove(tmpPath.toFile)
    }
  }

  override def service(request: HttpRequest, response: HttpResponse): Unit =
    serviceInternal(request, response)

  def serviceInternal(
      request: HttpRequest,
      response: HttpResponse,
      allowInputOutsideSandbox: Boolean = false,
      timeoutMs: Long = 60000 // 1 minute
    ): Unit = {

    def replyBadRequest(reason: String): Unit = {
      logger.info(s"Reply bad request: $reason")
      response.setStatusCode(HttpURLConnection.HTTP_BAD_REQUEST, reason)
    }

    def replyOk[T: io.circe.Encoder](result: T): Unit = {
      response.setContentType("application/json; charset=utf-8")
      response.getWriter.write(result.asJson(implicitly[io.circe.Encoder[T]]).toString)
      response.setStatusCode(HttpURLConnection.HTTP_OK)
    }

    def handleCompile(sandboxPath: Path, args: Seq[String], iFileSet: Set[String]): Unit = {

      replyOk {
        implicit val messageBuffer: MessageBuffer = new MessageBuffer

        val sandboxDirPrefix = sandboxPath.toString + File.separator

        def messages: Seq[ResponseMessage] = messageBuffer.messages map {
          toResponseMessage(_, sandboxDirPrefix)
        }

        try {
          val result = executorService.submit { () =>
            Compiler.parseArgs(messageBuffer, args, Some(sandboxPath)) flatMap {
              case (settings, source, params) =>
                settings pipe {
                  _.copy(
                    sandboxPathOpt = Some(sandboxPath),
                    parallel = false
                  )
                } pipe { settings =>
                  CommandLineInterface.compile(args, settings, source, params) map { _ =>
                    val oDir = settings.oPath.get
                    // Read all output files
                    // TODO: Ignore input files if odir == tmpdir
                    val files = if (oDir.toFile.exists) {
                      Map from {
                        Files.walk(oDir).iterator.asScala filter { path =>
                          // Return only regular files. Also ignore input files, in
                          // case the used specified the output directory to overlap
                          // and contain one of the input files (e.g.: '-o .')
                          path.toFile.isFile && !(iFileSet contains path.toFile.getCanonicalPath)
                        } map { file =>
                          file.toString.stripPrefix(sandboxDirPrefix) ->
                            (new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
                              .replaceAll(sandboxDirPrefix, "")
                        }
                      }
                    } else {
                      Map.empty[String, String]
                    }
                    // Form result
                    CompileResult("ok", files, messages)
                  }
                }
            } getOrElse {
              // Argument parsing had errors
              CompileResult("ok", Map.empty, messages)
            }
          }

          try {
            result.get(timeoutMs, TimeUnit.MILLISECONDS)
          } finally {
            if (!result.isDone) {
              result.cancel(true)
            }
          }
        } catch {
          case _: TimeoutException =>
            CompileResult("timeout", Map.empty, Seq.empty)
          case t: Throwable =>
            // TODO: Archive input for debug
            val cause = t match {
              case e: ExecutionException => e.getCause
              case _                     => t
            }
            val sw = new StringWriter
            cause.printStackTrace(new PrintWriter(sw))
            val trace = ResponseMessage("", 0, 0, 0, 0, "STDERR", Nil, sw.toString)
            CompileResult("crash", Map.empty, Seq(trace))
        }
      }
    }

    def handle(command: String, request: Json): Unit = {
      command match {
        case "describe" =>
          //////////////////////////////////////////////////////////////////////
          // WARNING: At a minimum, response to 'describe' must have the
          // apiVersion key so the client knows what to do next
          //////////////////////////////////////////////////////////////////////
          replyOk {
            DescribeResult(apiVersion, BuildInfo.version, BuildInfo.buildTime.toString)
          }
        case "compile" =>
          request.hcursor.get[JsonObject]("inputFiles") match {
            case Left(_) => replyBadRequest("No input files")
            case Right(files) =>
              withTmpDir { sandboxPath =>
                assert(sandboxPath.isAbsolute)
                // Write all input files to the temporary directory
                val (hasBadIFile, errorMessages, iFileSet) =
                  files.toIterable.iterator.foldLeft((false, Seq.empty[Error], Set.empty[String])) {
                    case (acc @ (true, _, _), _) => acc // Had error already
                    case ((false, messages, files), (fileName, fileContent)) =>
                      fileContent.as[String] match {
                        case Left(_) => (true, messages, files)
                        case Right(text) =>
                          val file = sandboxPath.resolve(fileName).toFile
                          if (
                            allowInputOutsideSandbox ||
                            file.getCanonicalFile.toPath.startsWith(sandboxPath)
                          ) {
                            file.createNewFile()
                            val w = new PrintWriter(file)
                            w.write(text)
                            w.close()
                            (false, messages, files + file.getCanonicalPath)
                          } else {
                            val message = Error(s"Input file $fileName is outside sandbox")
                            (false, messages :+ message, files)
                          }
                      }
                  }
                if (hasBadIFile) {
                  replyBadRequest("Bad input files")
                } else if (errorMessages.nonEmpty) {
                  replyOk {
                    val sandboxDirPrefix = sandboxPath.toString + File.separator
                    CompileResult(
                      "ok",
                      Map.empty,
                      errorMessages map { toResponseMessage(_, sandboxDirPrefix) }
                    )
                  }
                } else {
                  request.hcursor.get[String]("args") match {
                    case Left(_) =>
                      replyBadRequest("No args")
                    case Right(args) =>
                      handleCompile(sandboxPath, args.trim.split("\\s+").toSeq, iFileSet)
                  }
                }
              }
          }
        case other =>
          replyBadRequest(s"Unknown request type: '$other'")
      }
    }

    // Set CORS headers. Allow access from anywhere.
    response.appendHeader("Access-Control-Allow-Origin", "*");

    request.getMethod match {
      case "OPTIONS" =>
        // Set CORS headers. Allow POST with the Content-Type header and caches
        // preflight response for 3600s.
        response.appendHeader("Access-Control-Allow-Methods", "POST");
        response.appendHeader("Access-Control-Allow-Headers", "Content-Type");
        response.appendHeader("Access-Control-Max-Age", "3600");
        response.setStatusCode(HttpURLConnection.HTTP_NO_CONTENT);
      case "POST" =>
        request.getContentType.toScala map { _.toLowerCase } match {
          case Some("application/json; charset=utf-8") =>
            val text = request.getReader.lines.collect(Collectors.joining("\n"))
            io.circe.parser.parse(text) match {
              case Left(_) =>
                replyBadRequest("Request must be a JSON object")
              case Right(json) =>
                json.hcursor.downField("request").focus match {
                  case None => replyBadRequest("Request must have 'request' key")
                  case Some(focus) =>
                    focus.as[String] match {
                      case Left(_)        => replyBadRequest("Value of 'request' must be a string")
                      case Right(command) => handle(command, json)
                    }
                }
            }
          case t =>
            logger.info(s"Reply unsupported content type: $t")
            response.setStatusCode(HttpURLConnection.HTTP_UNSUPPORTED_TYPE)
        }
      case _ =>
        logger.info("Reply bad method")
        response.setStatusCode(HttpURLConnection.HTTP_BAD_METHOD)
    }
  }

}
