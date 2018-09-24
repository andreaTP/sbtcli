package org.akkajs.sbt

import scala.scalajs.js

import scala.concurrent.{Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.{global => ec}

import com.definitelyscala.node.Node

object SbtCli extends App {

  // Env variable to set the logging level of the CLI
  val LogLevelEnvVar = "SBTCLI_LOGLEVEL"
  val defaultLogLevel = wvlet.log.LogLevel("info")
  val logLevel = try {
    val ll =
      Node.process.env
        .asInstanceOf[js.Dynamic]
        .selectDynamic(LogLevelEnvVar)

    if (!js.isUndefined(ll))
      wvlet.log.LogLevel(ll.toString)
    else
      defaultLogLevel
  } catch {
    case _: Throwable => defaultLogLevel
  }

  CliLogger.logger.setLogLevel(logLevel)
  SbtLogger.logger.setLogLevel(logLevel)
  CodeLogger.logger.setLogLevel(logLevel)

  val argv = Node.process.argv
  CliLogger.logger.trace(s"Starting CLI with argv: $argv")

  /*Temporary location for this function ... */
  // TODO: completener to be completed
  // val completerFunction: js.Function1[String, js.Array[_]] = (line) => {
  //   val completions = js.Array[String]("...")
  //   js.Array(completions, line)
  // }

  if (argv.length <= 2) {
    // Interactive CLI

    // Typed interface is out-of-sync with github version and not working... :-S
    val readline = js.Dynamic.global.require("readline")
    val rl = readline.createInterface(
      js.Dynamic.literal(
        "input" -> Node.process.stdin,
        "output" -> Node.process.stdout,
        // "completer" -> completerFunction,
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
              Node.process.exit(0)
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
        .on("close", () => {
          Node.process.exit(0)
        })
    }
  } else {
    // Command line arguments

    argv.remove(0) // remove `node`
    argv.remove(0) // remove init script call
    for {
      sbtClient <- init()
    } yield {
      val commands =
        (for { str <- argv } yield {
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
        })
      for {
        _ <- Future.sequence(commands.toSeq)
      } yield {
        Node.process.exit(0)
      }
    }
  }

  def init(): Future[SocketClient] = {
    val baseDir = Node.process.cwd()
    val portfile = s"$baseDir/project/target/active.json"

    for {
      started <- ConnectSbt.startServerIfNeeded(portfile)
      if started == true
      sock <- ConnectSbt.connect(portfile).recoverWith {
        // retry after cleaning active.jsno
        case _ =>
          for {
            started <- ConnectSbt.startServerIfNeeded(portfile)
            if started == true
            s <- ConnectSbt.connect(portfile)
          } yield { s }
      }
      val sbtClient = {
        sock.on("error", () => {
          CliLogger.logger.error("Sbt server stopped.")
          Node.process.exit(-1)
        })
        new SocketClient(sock)
      }
      init <- sbtClient.send(InitCommand())
    } yield {
      sbtClient
    }
  }
}
