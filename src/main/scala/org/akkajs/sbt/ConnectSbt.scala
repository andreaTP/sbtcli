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

  // Sligthly modifyed from
  // https://github.com/semver/semver/issues/232
  val versionRegExp =
    """^([1-9]\d*)\.([2-9]\d*)\.(0|[1-9]\d*)(?:-((?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\.(?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?(?:\+([0-9a-zA-Z-]+(?:\.[0-9a-zA-Z-]+)*))?$"""

  def checkSbtVersion(versionfile: String): Future[Unit] = {
    val ret = Promise[Unit]()
    Fs.readFile(
      versionfile,
      (err, content) => {
        try {
          val sbtVersion =
            content
              .toString()
              .split("\n")
              .find(_.contains("sbt.version"))
              .map(
                str =>
                  str
                    .replace("sbt.version", "")
                    .replace("=", "")
                    .trim())

          val correct =
            versionRegExp.r.pattern.matcher(sbtVersion.get).matches()

          if (correct) {
            ret.trySuccess(())
          } else {
            CliLogger.logger.error("sbtcli works only with sbt version > 1.2.X")
            Node.process.exit(-1)
          }
        } catch {
          case _: Throwable =>
            ret.trySuccess(())
            CliLogger.logger.warn(
              "Cannot determine the version of sbt. Proceed at your own risk.")
        }
      }
    )
    ret.future
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

  def startServerIfNeeded(portfile: String,
                          startupTimeout: Int): Future[Boolean] = {
    val startedProm = Promise[Boolean]
    Fs.exists(portfile, (exists) => {
      if (exists)
        startedProm.success(true)
      else
        forkServer(portfile, startedProm, startupTimeout)
    })
    startedProm.future
  }

  def forkServer(portfile: String,
                 startedProm: Promise[Boolean],
                 startupTimeout: Int) = {
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

    val spawnOptions =
      js.Dynamic.literal().asInstanceOf[SpawnOptions]
    spawnOptions.detached = true
    spawnOptions.stdio = "ignore"

    val sbtProcess = Child_process.spawn(
      cmd,
      js.Array[String](
        // I take total control over output color and formatting
        "-Dsbt.log.noformat=true"
      ),
      spawnOptions
    )

    sbtProcess.unref()

    timeout = Node.setTimeout(
      () => {
        CliLogger.logger.error(s"timeout waiting for server.")
        if (check != null)
          Node.clearInterval(check)

        startedProm.success(false)
      },
      startupTimeout
    )
  }
}
