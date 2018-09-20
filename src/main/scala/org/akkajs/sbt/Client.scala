package org.akkajs.sbt

import scala.scalajs.js

import scala.concurrent.{Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.{global => ec}

import com.definitelyscala.node.Node
import com.definitelyscala.node

object SbtCli extends App {

  // Env variable to set the logging level of the CLI
  val LogLevelEnvVar = "SBTCLI_LOGLEVEL"
  val logLevel = try {
    val ll = Node.process.env.selectDynamic(LogLevelEnvVar)
    wvlet.log.LogLevel(ll)
  } catch {
    case _: Throwable =>
      wvlet.log.LogLevel("info")
  }

  CliLogger.logger.setLogLevel(logLevel)
  SbtLogger.logger.setLogLevel(logLevel)
  CodeLogger.logger.setLogLevel(logLevel)

  val argv = node.Node.process.argv
  CliLogger.logger.trace(s"Starting CLI with argv: $argv")

  if (argv.length <= 2) {
    // Interactive CLI

    val readline = js.Dynamic.global.require("readline")
    val rl = readline.createInterface(
      js.Dynamic.literal(
        "input" -> node.Node.process.stdin,
        "output" -> node.Node.process.stdout,
        "prompt" -> ">+> "
      ))

    for {
      sbtClient <- init()
    } {
      rl.prompt(true)
      rl.on(
        "line",
        (line: js.Dynamic) => {
          val originalCmd = line.toString()
          if (originalCmd.trim == "exit") {
            rl.close()
            node.Node.process.exit(0)
          }
          val cmd = {
            if (originalCmd == "shutdown") "exit"
            else originalCmd
          }
          for {
            result <- sbtClient.send(ExecCommand(cmd))
          } yield {
            result.print()
            rl.prompt(true)
          }
        }
      )
    }
  } else {
    // Command line arguments

    argv.remove(0) // remove `node`
    argv.remove(0) // remove init script call
    for {
      sbtClient <- init()
      result <- sbtClient.send(ExecCommand(cmd))
    } yield {
      for {str <- argv} yield {
        val cmd = {
          if (str == "shutdown") "exit"
          else str
        }
        CliLogger.logger.info(s"Executing command: $cmd")
        for {
          result <- sbtClient.send(ExecCommand(cmd))
        } yield {
          result.print()
        }
      }
      node.Node.process.exit(0)
    }
  }

  def init(): Future[SocketClient] = {
    val baseDir = node.Node.process.cwd()
    val portfile = s"$baseDir/project/target/active.json"

    for {
      started <- ConnectSbt.startServerIfNeeded(portfile)
      if started == true
      sock <- ConnectSbt.connect(portfile)
      val sbtClient = {
        sock.on("error", () => {
          CliLogger.logger.error("Sbt server stopped.")
          node.Node.process.exit(-1)
        })
        new SocketClient(sock)
      }
      init <- sbtClient.send(InitCommand())
    } yield {
      sbtClient
    }
  }
}
