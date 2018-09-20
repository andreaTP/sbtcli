package org.akkajs.sbt

import wvlet.log._

object CustomSimpleLogFormatter extends LogFormatter {
  import LogFormatter._
  override def formatLog(r: LogRecord): String = {
    val log = s"[${withColor(Console.WHITE, r.leafLoggerName)}/${highlightLog(
      r.level,
      r.level.name)}] ${highlightLog(r.level, r.getMessage)}"
    appendStackTrace(log, r)
  }
}

object CliLogger {

  val logger = {
    val res = Logger("cli")
    res.setFormatter(CustomSimpleLogFormatter)
    res
  }

}

object SbtLogger {

  val logger = {
    val res = Logger("sbt")
    res.setFormatter(CustomSimpleLogFormatter)
    res
  }

  val fakeLogSource = LogSource("", "", 0, 0)

  def log(msg: String, level: Int) =
    logger.log(LogLevel.values(level), fakeLogSource, msg)
}

object CodeLogger {

  val logger = {
    val res = Logger("sbt-diag")
    res.setFormatter(LogFormatter.SourceCodeLogFormatter)
    res
  }

  val fakeLogSource =
    // LogSource(path: String, fileName: String, line: Int, col: Int)
    LogSource("", "", 0, 0)

  def log(msg: String, level: Int) =
    logger.log(LogLevel.values(level), fakeLogSource, msg)
}
