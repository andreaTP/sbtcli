package org.akkajs.sbt

import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.{global => ec}

import scala.scalajs.js

import com.definitelyscala.node.Node
import com.definitelyscala.node.Buffer
import com.definitelyscala.node.fs.Fs
import com.definitelyscala.node.net.Socket

class SocketClient(
    sock: Socket
) {

  val cwd = Node.process.cwd()

  val inFlight = mutable.Map[String, Promise[Event]]()

  var buffer: String = ""

  sock.on(
    "data",
    (data: js.Dynamic) => {
      val str = data.toString()
      buffer = buffer + str
      parseBuffer()
    }
  )

  def parseBuffer(): Unit = {
    if (buffer.contains("Content-Length:")) {
      val start = buffer.indexOf('{')
      val init = buffer.substring(start, buffer.length)

      val next = init.indexOfSlice("Content-")

      val msg =
        if (next == -1) init
        else init.substring(0, next)

      def trimBuffer() = {
        if (next == -1) buffer = ""
        else buffer = buffer.substring(start + msg.length, buffer.length)
      }

      try {
        val json = js.JSON.parse(msg)
        try {
          onMessage(json)
        } catch {
          case err: Throwable =>
            CliLogger.logger.error(s"Unhandled message ${msg}.")
        }
        trimBuffer()
        parseBuffer()
      } catch {
        case _: Throwable =>
          //not yet a valid json message
          if (buffer != "" && !buffer.startsWith("Content-")) {
            // trailing junk to throw away
            val start = buffer.indexOfSlice("Content-")
            buffer = buffer.substring(start, buffer.length)
            parseBuffer()
          }
      }
    }
  }

  def onMessage(msg: js.Dynamic) = {
    CliLogger.logger.trace(s"receive: ${js.JSON.stringify(msg)}")
    val id = msg.id.toString

    inFlight.get(id) match {
      case Some(prom) =>
        inFlight -= id
        diagnosticsDone = Seq[String]()
        prom.success(Result(msg))
      case _ if (!js.isUndefined(msg.method)) =>
        onNotification(msg)
      case _ =>
        CliLogger.logger.error(s"unmatched message from server")
        CliLogger.logger.debug(js.JSON.stringify(msg))
    }
  }

  var diagnosticsDone = Seq[String]()

  def onNotification(json: js.Dynamic): Unit = {
    json.method.toString match {
      case "window/logMessage" =>
        val content = json.params
        SbtLogger.log(content.message.toString, content.`type`.toString.toInt)
      case "textDocument/publishDiagnostics" =>
        val diags = json.params.diagnostics.asInstanceOf[js.Array[js.Dynamic]]
        val fileUri =
          if (diags.size > 0) {
            new java.net.URI(json.params.uri.toString())
          } else null

        for {
          diag <- diags.filter(x =>
            !diagnosticsDone.contains(js.JSON.stringify(x)))
        } yield {
          val message = diag.message.toString
          val severity = diag.severity.asInstanceOf[Int]

          val startLine = diag.range.start.line.asInstanceOf[Int] + 1
          val endLine = diag.range.end.line.asInstanceOf[Int] + 1

          val colStart = diag.range.start.character.asInstanceOf[Int] + 1
          // val colEnd = diag.range.start.character.asInstanceOf[Int] + 1

          val logSource =
            wvlet.log.LogSource(
              path = "",
              fileName = "." + fileUri.getPath().replace(cwd, ""),
              line = startLine,
              col = colStart
            )

          Fs.readFile(
            fileUri.getPath(),
            (err, data) => {
              ErrorLogger.log(message, severity, logSource)
              if (err == null) {
                val lines =
                  data
                    .toString()
                    .split("\n", endLine + 2)
                    .drop(startLine - 1)
                    .take(startLine - endLine + 1)

                lines.zipWithIndex.foreach {
                  case (line, i) =>
                    val startCol =
                      if (i == 0) colStart // - 1
                      else 0

                    val ls = wvlet.log.LogSource(
                      path = "",
                      fileName = "",
                      line = startLine + i,
                      col = startCol
                    )

                    CodeLogger.log(line, severity, ls)
                }
              }
            }
          )
        }

        diagnosticsDone = diagnosticsDone ++ diags
          .map(js.JSON.stringify(_))
          .toSeq
    }
  }

  var lastSent: Option[String] = None

  def send(cmd: Command): Future[Event] = {
    val answer = Promise[Event]()
    val id = cmd.execId.toString
    lastSent = Some(id)
    inFlight += (id -> answer)
    rawSend(cmd)
    answer.future
  }

  private def rawSend(cmd: Command) = {
    val serialized = cmd.serialize()
    CliLogger.logger.trace(s"send: ${serialized}")
    val str = s"Content-Length: ${serialized.length + 2}\r\n\r\n$serialized\r\n"
    sock.write(Buffer.from(str, "UTF-8"))
  }
}
