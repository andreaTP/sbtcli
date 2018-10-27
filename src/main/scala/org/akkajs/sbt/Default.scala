package org.akkajs.sbt

import scala.scalajs.js
import scala.util.Try
import caseapp._

import com.definitelyscala.node.Node

object Default {

  // Env variable to set the logging level of the CLI
  val LogLevelEnvVar = "SBTCLI_LOGLEVEL"

  val version = Try {
    js.Dynamic.global.require("../package.json").version.toString
  }.toOption.getOrElse("")

  val logLevelFallback = Try {
    val ll =
      Node.process.env
        .asInstanceOf[js.Dynamic]
        .selectDynamic(LogLevelEnvVar)

    if (!js.isUndefined(ll)) {
      wvlet.log.LogLevel(ll.toString)
      ll.toString
    } else throw new Exception("log level env var not defined or not valid")
  }.toOption.getOrElse("info")

  @AppName("SbtCli")
  @AppVersion(version)
  @ProgName("sbtcli")
  case class CmdLineOptions(
      @ExtraName("c")
      @HelpMessage(
        "[experimental] Re-trigger the command on changes to .scala or .java files (only non-interactive mode)")
      continue: Boolean = false,
      @ExtraName("ll")
      @HelpMessage("Log level to be used")
      logLevel: String = logLevelFallback,
      @ExtraName("st")
      @HelpMessage("Hard Sbt start timeout (in ms)")
      startupTimeout: Int = 90000,
      @ExtraName("sc")
      @HelpMessage("Command to be used to launch sbt")
      sbtCmd: String = "sbt",
  )

  def errorExit() = {
    Node.process.exit(-1)
    throw new Exception("unreachable")
  }

  def cmdLineParsingError() = {
    CliLogger.logger.info("Not a valid command")
    CliLogger.logger.info(CaseApp.helpMessage[CmdLineOptions])
    errorExit()
  }

  def parseCmdLine(argv: js.Array[String]) = {
    argv.remove(0) // remove `node`
    argv.remove(0) // remove init script call

    CaseApp
      .detailedParseWithHelp[CmdLineOptions](argv.toSeq) match {
      case Right((parsed, help, usage, args)) =>
        if (usage) {
          CliLogger.logger.info(CaseApp.usageMessage[CmdLineOptions])
          errorExit()
        } else if (help) {
          CliLogger.logger.info(CaseApp.helpMessage[CmdLineOptions])
          errorExit()
        } else {
          parsed match {
            case Right(opts) => (opts, args.all)
            case Left(_)     => cmdLineParsingError()
          }
        }
      case Left(_) => cmdLineParsingError()
    }
  }

}
