package org.akkajs.sbt

import scala.scalajs.js

sealed trait Command {
  lazy val execId = java.util.UUID.randomUUID
  def serialize(): String

  protected def onWire(method: String, params: js.Dynamic) = {
    js.Dynamic.literal(
      "jsonrpc" -> "2.0",
      "id" -> execId.toString(),
      "method" -> method,
      "params" -> params
    )
  }
}
final case class InitCommand() extends Command {
  def serialize() = {
    js.JSON.stringify(
      onWire(
        "initialize",
        js.Dynamic.literal("initializationOptions" -> js.Dynamic.literal())
      ))
  }
}
final case class ExecCommand(command: String) extends Command {
  def serialize() = {
    js.JSON.stringify(
      onWire(
        "sbt/exec",
        js.Dynamic.literal("commandLine" -> command)
      ))
  }
}
final case class SettingQuery(setting: String) extends Command {
  def serialize() = {
    js.JSON.stringify(
      onWire(
        "sbt/setting",
        js.Dynamic.literal("setting" -> setting)
      ))
  }
}
final case class CancelRequest(id: String) extends Command {
  def serialize() = {
    js.JSON.stringify(
      onWire(
        "sbt/cancelRequest",
        js.Dynamic.literal("id" -> id)
      ))
  }
}
final case class CompletionRequest(query: String) extends Command {
  def serialize() = {
    js.JSON.stringify(
      onWire(
        "sbt/completion",
        js.Dynamic.literal("query" -> query)
      ))
  }
}

sealed trait Event {
  val json: js.Dynamic
  def print(): Unit
  def completions(): js.Array[String] = {
    try {
      json.result.items.asInstanceOf[js.Array[String]]
    } catch {
      case _: Throwable => js.Array[String]()
    }
  }
}

final case class Result(val json: js.Dynamic) extends Event {
  def print(): Unit = {
    if (!js.isUndefined(json.result)) {
      CliLogger.logger.info("completed")
    } else {
      CliLogger.logger.error("error executing command")
    }
  }
}
