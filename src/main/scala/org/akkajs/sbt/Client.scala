
import scala.scalajs.js

import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.{global => ec}

import java.util.UUID

import com.definitelyscala.node

import wvlet.log._

trait Command {
  lazy val execId = java.util.UUID.randomUUID
  def serialize(): String
}
case class InitCommand() extends Command {
  def serialize() = {
    js.JSON.stringify(
      js.Dynamic.literal(
        "jsonrpc" -> "2.0",
        "id" -> execId.toString(),
        "method" -> "initialize",
        "params" -> js.Dynamic.literal("initializationOptions" -> js.Dynamic.literal())
      )
    )
  }
}
case class ExecCommand(command: String) extends Command {
  def serialize() = {
    js.JSON.stringify(
      js.Dynamic.literal(
        "jsonrpc" -> "2.0",
        "id" -> execId.toString(),
        "method" -> "sbt/exec",
        "params" -> js.Dynamic.literal("commandLine" -> command)
      )
    )
  }
}

trait Answer {
  def print() : Unit
}
case class Result(json: js.Dynamic) extends Answer {
  def print(): Unit = {
    if (json.result != null) {
      println("completed")
    } else {
      System.err.println("completed with errors")
    }
  }
}

class SocketClient(
    sock: node.net.Socket
  ) {

  val inFlight = mutable.Map[String, Promise[Answer]]()

  sock.on("data", (data: js.Dynamic) => {
    val str = data.toString()
    // TODO properly parse input
    val start = str.indexOf('{')
    val end = {
      val next = str.indexOf("Content-Length:")
      if (next == -1) str.length
      else next
    }

    val json = js.JSON.parse(str.substring(start, str.length))
    val id = json.id.toString

    inFlight.get(id) match {
      case Some(prom) =>
        inFlight -= id
        prom.success(Result(json))
      case _ if json.method != null =>
        onNotification(json)
      case _ =>
        System.err.println(s"unmatched message from server $json")
        println(data)
    }
  })

  def onNotification(json: js.Dynamic): Unit = {
    json.method.toString match {
      case "window/logMessage" =>
        val content = json.params
        SbtCli.log(content.message.toString, content.`type`.toString.toInt)
      case "textDocument/publishDiagnostics" =>
        println("To be done")
    }
  }


  def send(cmd: Command): Future[Answer] = {
    val answer = Promise[Answer]()
    val id = cmd.execId.toString
    inFlight += (id -> answer)
    rawSend(cmd)
    answer.future
  }

  private def rawSend(cmd: Command) = {
    val serialized = cmd.serialize()
    val str = s"Content-Length: ${serialized.length + 2}\r\n\r\n$serialized\r\n"
    sock.write(node.Buffer.from(str, "UTF-8"))
  }
}

object SbtCli extends App with LogSupport {

  // TODO: make it customizable
  // wvlet.log.setDefaultLogLevel("debug")
  wvlet.log.Logger.setDefaultLogLevel(wvlet.log.LogLevel("info"))
  wvlet.log.Logger.setDefaultFormatter(wvlet.log.LogFormatter.SimpleLogFormatter)

  val argv = node.Node.process.argv
  if (argv.length <= 1) {
    val readline = js.Dynamic.global.require("readline")
    val rl = readline.createInterface(js.Dynamic.literal(
      "input" -> node.Node.process.stdin,
      "output" -> node.Node.process.stdout,
      "prompt" -> ">+>"
    ))

    for {
      sbtClient <- init()
    } {
      rl.prompt()
      rl.on("line", (line: js.Dynamic) => {
        val cmd = line.toString()
        for {
          result <- sbtClient.send(ExecCommand(cmd))
        } yield {
          result.print()

          if (cmd.trim == "exit") {
            rl.close()
            node.Node.process.exit(0)
          } else {
            rl.prompt()
          }
        }
      })
    }
  } else {
    // TODO: check multiple command execution ....
    val cmd = {
      argv.remove(0)
      val args = argv.mkString(" ")
      if (args.trim == "shutdown") "exit"
      else args
    }
    println("with command "+cmd)
    for {
      sbtClient <- init()
      result <- sbtClient.send(ExecCommand(cmd))
    } yield {
      result.print()
      node.Node.process.exit(0)
    }
  }

  def init(): Future[SocketClient] = {
    val baseDir = node.Node.process.cwd()
    val portfile = s"$baseDir/project/target/active.json"

    (for {
      started <- startServerIfNeeded(portfile)
      if started == true
      sock <- connect(portfile)
    } yield {
      sock.on("error", () => {
        System.err.println("Sbt server stopped.")
        node.Node.process.exit(-1)
      })

      val sbtClient = new SocketClient(sock)
      for {
        init <- sbtClient.send(InitCommand())
      } yield {
        sbtClient
      }
    }).flatten
  }

  def connectionFailure(portfile: String, res: Promise[node.net.Socket]) = {
    node.fs.Fs.unlink(portfile, (err) => {
      if (err != null) {
        sys.error("Cannot connect to socket and cannot remove current Sbt active.json file")
        node.Node.process.exit(-1)
      } else {
        connect(portfile, res)
      }
    })
  }

  def connect(portfile: String, res: Promise[node.net.Socket] = Promise[node.net.Socket]()): Future[node.net.Socket] = {
    node.fs.Fs.readFile(portfile, (err, content) => {
      if (err == null) {
        val json = js.JSON.parse(content.toString())
        val uri = new java.net.URI(json.uri.toString())

        val socket = node.net.Net.connect(uri.getPath(), () => {})
        socket.on("connect", () => {
            println("Connected to Sbt Server")
            res.success(socket)
        })
        socket.on("error", (err: js.Dynamic) => {
          connectionFailure(portfile, res)
        })
      } else {
        connectionFailure(portfile, res)
      }
    })

    res.future
  }

  def startServerIfNeeded(portfile: String): Future[Boolean] = {
    
    val startedProm = Promise[Boolean]
    node.fs.Fs.exists(portfile, (exists) => {
      if (exists)
        startedProm.success(true)
      else
        forkServer(portfile, startedProm)
    })
    startedProm.future
  }

  def forkServer(portfile: String, startedProm: Promise[Boolean]) = {
    println("Forking and starting an sbt server")

    val cmd = "sbt"
    var timeout: node.Timer = null
    var check: node.Timer = null

    check = node.Node.setInterval(() => {
      node.fs.Fs.exists(portfile, (exists) => {
        if (exists) {
          println("server found")
          if (timeout != null)
            node.Node.clearTimeout(timeout)
          if (check != null)
            node.Node.clearInterval(check)

          startedProm.success(true)    
        } else {
          println("waiting for server ...")
        }
      })
    }, 500)

    timeout = node.Node.setTimeout(() => {
      sys.error(s"timeout. $portfile is not found.")
      if (check != null)
        node.Node.clearInterval(check)
      startedProm.success(false)
    }, 90000)

    val spawnOptions = js.Dynamic.literal().asInstanceOf[node.child_process.SpawnOptions]
    spawnOptions.detached = true
    
    node.child_process.Child_process.spawn(
      cmd,
      js.Array[String](),
      spawnOptions      
    )
  }

  val fakeLogSource =
    // LogSource(path: String, fileName: String, line: Int, col: Int)
    LogSource("", "", 0, 0)

  def log(msg: String, level: Int) = {
    logger.log(LogLevel.values(level), fakeLogSource, msg)
  }
}
