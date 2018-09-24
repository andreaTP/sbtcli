package org.akkajs.sbt

import scala.concurrent.{Future, Promise}

import scala.scalajs.js

import com.definitelyscala.node.{Node, Timer}
import com.definitelyscala.node.fs.Fs
import com.definitelyscala.node.child_process.{Child_process, SpawnOptions}
import com.definitelyscala.node.net.{Socket, Net}

object ConnectSbt {

  def connectionFailure(portfile: String, res: Promise[Socket]) = {
    Fs.unlink(
      portfile,
      (err) => {
        if (err != null) {
          CliLogger.logger.error(
            "Cannot connect to socket and cannot remove current Sbt active.json file")
          Node.process.exit(-1)
        } else {
          res.failure(new Exception("cannot connect to available port"))
        }
      }
    )
  }

  def connect(portfile: String,
              res: Promise[Socket] = Promise[Socket]()): Future[Socket] = {
    Fs.readFile(
      portfile,
      (err, content) => {
        try {
          val json = js.JSON.parse(content.toString())
          val uri = new java.net.URI(json.uri.toString())

          val socket = Net.connect(uri.getPath(), () => {})
          socket.on("connect", () => {
            CliLogger.logger.info("Connected to Sbt Server")
            res.success(socket)
          })
          socket.on("error", (err: js.Dynamic) => {
            connectionFailure(portfile, res)
          })
        } catch {
          case _: Throwable =>
            connectionFailure(portfile, res)
        }
      }
    )

    res.future
  }

  def startServerIfNeeded(portfile: String): Future[Boolean] = {
    val startedProm = Promise[Boolean]
    Fs.exists(portfile, (exists) => {
      if (exists)
        startedProm.success(true)
      else
        forkServer(portfile, startedProm)
    })
    startedProm.future
  }

  def forkServer(portfile: String, startedProm: Promise[Boolean]) = {
    CliLogger.logger.info("Forking and starting an sbt server")

    val cmd = "sbt"
    var timeout: Timer = null
    var check: Timer = null

    check = Node.setInterval(
      () => {
        Fs.exists(
          portfile,
          (exists) => {
            if (exists) {
              CliLogger.logger.info("server found")
              if (timeout != null)
                Node.clearTimeout(timeout)
              if (check != null)
                Node.clearInterval(check)

              startedProm.success(true)
            } else {
              CliLogger.logger.trace("waiting for server ...")
            }
          }
        )
      },
      500
    )

    timeout = Node.setTimeout(() => {
      CliLogger.logger.error(s"timeout. $portfile is not found.")
      if (check != null)
        Node.clearInterval(check)
      startedProm.success(false)
    }, 90000)

    val spawnOptions =
      js.Dynamic.literal().asInstanceOf[SpawnOptions]
    spawnOptions.detached = true

    Child_process.spawn(
      cmd,
      js.Array[String](),
      spawnOptions
    )
  }
}
