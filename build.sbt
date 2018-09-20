
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

scalafmtOnCompile in ThisBuild := true

val deploy = taskKey[Unit]("Deploy the CLI")

deploy := {
  val opt = (fullOptJS in Compile).value.data
  val target = baseDirectory.value / "lib" / "sbtcli.js"

  IO.copy(Seq((opt -> target)), CopyOptions(true, false, false))
}
