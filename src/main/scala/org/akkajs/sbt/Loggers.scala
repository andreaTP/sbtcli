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

object CustomSourceCodeLogFormatter extends LogFormatter {
  import LogFormatter._
  override def formatLog(r: LogRecord): String = {
    val loc =
      r.source
        .map(source => s" ${withColor(Console.BLUE, s"- ${source.fileLoc}")}")
        .getOrElse("")

    val logTag = highlightLog(r.level, r.level.name)
    val log =
      f"[${withColor(Console.WHITE, r.leafLoggerName)}/${logTag}%14s]${loc}\n${highlightLog(r.level, r.getMessage)}"
    appendStackTrace(log, r)
  }
}

object CodeLogger {

  val logger = {
    val res = Logger("compiler")
    res.setFormatter(CustomSourceCodeLogFormatter)
    res
  }

  def log(msg: String, level: Int, logSource: LogSource) =
    logger.log(LogLevel.values(level), logSource, msg)
}
