package org.akkajs.sbt

import scala.scalajs.js

import scala.util.{Success, Failure, Try}
import scala.concurrent.{Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.{global => ec}

import com.definitelyscala.node.Node
import com.definitelyscala.node.fs.{Fs, Stats}
import com.definitelyscala.node.path.Path

import LightColors._

object SbtCli extends App {

  val argv = Node.process.argv

  val (options, sbtCmds) = Default.parseCmdLine(argv)

  val logLevel = wvlet.log.LogLevel(options.logLevel)

  CliLogger.logger.setLogLevel(logLevel)
  SbtLogger.logger.setLogLevel(logLevel)
  CodeLogger.logger.setLogLevel(logLevel)

  CliLogger.logger.trace(s"Starting CLI with argv: ${Node.process.argv}")
  if (sbtCmds.size <= 0) {
    // Interactive Shell

    // Typed interface is out-of-sync with github version and not working... :-S
    val readline = js.Dynamic.global.require("readline")

    for {
      sbtClient <- init(options.startupTimeout, options.sbtCmd)
      val completionAvailable = ConnectSbt.checkCurrentSbtVersion(3)
      baseCompletions <- {
        if (completionAvailable) sbtClient.send(CompletionRequest(""))
        else
          Future { new Result(js.Dynamic.literal()) } // TODO: have a static predefined list?
      }
    } {

      val rl = readline.createInterface(
        js.Dynamic.literal(
          "input" -> Node.process.stdin,
          "output" -> Node.process.stdout,
          "completer" -> completerFunction(completionAvailable,
                                           sbtClient,
                                           baseCompletions.completions()),
          "prompt" -> (s"$LIGHT_CYAN>+> " + Console.RESET)
        ))

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
        .on(
          "SIGINT",
          () => {
            // pressed Ctrl-C
            val lastCmd = sbtClient.lastSent
            sbtClient.lastSent match {
              case Some(lastId) =>
                for {
                  result <- sbtClient.send(CancelRequest(lastId))
                } yield {
                  result.print()
                  rl.prompt(true)
                }
              case _ =>
                // no command is in queue
                CliLogger.logger.info(s"To exit type: `exit` + Enter")
            }
          }
        )
        .on("close", () => {
          Node.process.exit(0)
        })
    }
  } else {
    // Non-interactive mode

    for {
      sbtClient <- init(options.startupTimeout, options.sbtCmd)
    } yield {
      def commands =
        (for { str <- sbtCmds } yield {
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

      def executionError(err: Throwable) = {
        CliLogger.logger.error(s"Error: ${err.getMessage}")
        Node.process.exit(-1)
      }

      var changed = Promise[Boolean]()

      val excludedDirs = Seq(
        "target",
        "node_modules"
      )

      var watched = List[String]()

      def checkStats(parent: String, stats: Stats) = {
        Try {
          if (stats.isDirectory()) {
            watched = watched :+ parent
            watch(parent)
            Fs.readdir(
              parent,
              (error, files) => recurseOnFiles(parent, files)
            )
          }
        }
      }

      def recurseOnFiles(parent: String, files: js.Array[String]) = {
        Try {
          files.toArray.foreach(fn => {
            val fileName = fn.toString
            if (!fileName
                  .startsWith(".") && !(excludedDirs.contains(fileName))) {
              watchDesc(Path.join(parent, fileName))
            }
          })
        }
      }

      def watchDesc(parent: String): Unit = {
        if (!watched.contains(parent)) {
          Fs.stat(
            parent,
            (error, stats) => checkStats(parent, stats)
          )
        }
      }

      // file watch can be implemented registering a file watcher:
      // https://github.com/scalacenter/bsp/blob/master/docs/bsp.md#register-file-watcher-request
      def watch(dir: String) = {
        Fs.watch(
          dir,
          js.Dynamic.literal("recursive" -> false),
          (eventType, fn) => {
            val fileName = fn.toString
            if (fileName.toString.endsWith(".scala") ||
                fileName.toString.endsWith(".java")) {
              changed.trySuccess(true)
              watchDesc(Path.join(dir, fileName))
            }
          }
        )
      }

      def executeOnChange(): Future[Unit] = {
        if (watched.isEmpty) watchDesc(".")

        CliLogger.logger.info("Waiting for changes")

        (for {
          _ <- changed.future
          _ <- Future.sequence(commands.toSeq)
        } yield {
          changed = Promise[Boolean]()
        }).andThen {
          case Success(_)   => executeOnChange()
          case Failure(err) => executionError(err)
        }
      }

      (for {
        _ <- Future.sequence(commands.toSeq)
      } yield {}).andThen {
        case Success(_) =>
          if (!options.continue)
            Node.process.exit(0)
          else
            executeOnChange()
        case Failure(err) => executionError(err)
      }
    }
  }

  type CompleterResult = js.Tuple2[js.Array[String], String]
  def completerFunction(completionAvailable: Boolean,
                        client: SocketClient,
                        baseCompletions: js.Array[String] = js.Array())
    : js.Function2[String, js.Function2[js.Any, CompleterResult, Unit], Unit] =
    (line, callback) => {
      def fallback() = {
        CliLogger.logger.warn("No matches. Possible suggestions are:")

        val topCompletions = baseCompletions
          .filterNot(_.startsWith("ProjectRef"))
          .map(x => (LongestCommonSeq(x, line), x))
          .sortWith(_._1 > _._1)
          .filter(_._1 > 0)
          .take(20) // this can come from command line options
          .map(_._2)
          .sortWith(_.size < _.size)

        callback(null, (topCompletions, line))
      }

      if (completionAvailable)
        for {
          result <- client.send(CompletionRequest(line))
        } yield {
          // full autocomplete only after second TAB
          // https://github.com/addaleax/node/commit/dca1f272a2f05043f1ad2bc61093d7ffbe3f2788
          if (result.completions().size > 0)
            callback(null, (result.completions(), line))
          else
            fallback()
        } else fallback()
    }

  def init(startupTimeout: Int, cmd: String): Future[SocketClient] = {
    val baseDir = Node.process.cwd()
    val versionfile = s"$baseDir/project/build.properties"
    val portfile = s"$baseDir/project/target/active.json"

    (for {
      _ <- ConnectSbt.checkSbtVersion(versionfile)
      started <- ConnectSbt.startServerIfNeeded(portfile, startupTimeout, cmd)
      if started == true
      sock <- ConnectSbt.connect(portfile).recoverWith {
        // retry after cleaning active.json
        case _ =>
          for {
            started <- ConnectSbt.startServerIfNeeded(portfile,
                                                      startupTimeout,
                                                      cmd)
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
    }).recover {
      case _ =>
        Node.process.exit(-1)
        throw new Exception("unreachable")
    }
  }
}
