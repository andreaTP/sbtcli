
enablePlugins(ScalaJSPlugin)

name := "sbtcli"

organization := "org.akka-js"

scalaVersion := "2.12.7"

fork in run := true

cancelable in Global := true

scalaJSModuleKind := ModuleKind.CommonJSModule

skip in packageJSDependencies := false

scalaJSUseMainModuleInitializer := true

resolvers += Resolver.jcenterRepo

testFrameworks += new TestFramework("utest.runner.Framework")

libraryDependencies ++= Seq(
  "org.wvlet.airframe" %%% "airframe-log" % "0.65", 
  "com.definitelyscala" %%% "scala-js-node" % "1.0.1",
  "com.github.alexarchambault" %%% "case-app" % "2.0.0-M3",
  "com.lihaoyi" %%% "utest" % "0.6.5" % "test"
)

scalafmtOnCompile in ThisBuild := true

val shebang = "#!/usr/bin/env node"

val prelude = """'use strict';
                 |global.require = require;
                 |global.fs = require('fs');
                 |global.path = require('path');
                 |global.child_process = require('child_process');
                 |global.net = require('net');
                 |global.readline = require('readline');
                 |""".stripMargin

val require = "require('../lib/sbtcli.js');"

val deploy = taskKey[Unit]("Deploy the CLI")

deploy := {
  writeBin.value

  val opt = (fullOptJS in Compile).value.data
  val target = baseDirectory.value / "lib" / "sbtcli.js"

  IO.copy(Seq((opt -> target)), CopyOptions(true, false, false))
}

val buildBinary = taskKey[Unit]("Build binaries. - first run `npm install` to install `pkg`")

buildBinary := {
  val targetFile = target.value / "binary" / "sbtcli.js"

  IO.write(targetFile, prelude)
  IO.append(targetFile, IO.read((fullOptJS in Compile).value.data))

  import scala.sys.process._
  "npm run buildBinary" !
}

val writeBin = taskKey[Unit]("Write executable bin file")

writeBin := {
  val binFile = baseDirectory.value / "bin" / "sbtcli"

  IO.write(binFile, s"$shebang\n$prelude\n$require")
  IO.chmod("rwxr-xr-x", binFile)
}
