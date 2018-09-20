
enablePlugins(ScalaJSPlugin)

name := "sbtcli"

organization := "org.akka-js"

scalaVersion := "2.12.6"

fork in run := true

cancelable in Global := true

scalaJSModuleKind := ModuleKind.CommonJSModule

skip in packageJSDependencies := false

scalaJSUseMainModuleInitializer := true

resolvers += Resolver.jcenterRepo

libraryDependencies ++= Seq(
  "org.wvlet.airframe" %%% "airframe-log" % "0.65", 
  "com.definitelyscala" %%% "scala-js-node" % "1.0.1",
)

run := {
  (fastOptJS in Compile).value
  import scala.sys.process._
  "./run.sh" !
}

scalafmtOnCompile in ThisBuild := true
