organization := "com.codacy"

name := "orientdb-test"

version := "1.0.0-SNAPSHOT"

scalaVersion := "2.11.11"

lazy val orientdbTest = (project in file("."))
  .enablePlugins(PlayScala)
  .settings(libraryDependencies ++= Seq(
    evolutions,
    jdbc,

    "org.typelevel" %% "cats" % "0.9.0",

    //    "com.typesafe.play" %% "play-slick" % "3.0.0",
    //    "com.typesafe.play" %% "play-slick-evolutions" % "3.0.0",

    //  "com.orientechnologies" % "orientdb-core" % "2.2.12",
    //  "com.orientechnologies" % "orientdb-jdbc" % "2.2.12",
    //  "com.orientechnologies" % "orientdb-client" % "2.2.12",
    //  "com.orientechnologies" % "orientdb-graphdb" % "2.2.12",

    "com.michaelpollmeier" %% "gremlin-scala" % "3.2.5.2",
    "com.michaelpollmeier" % "orientdb-gremlin" % "3.2.3.0",
    //  "com.orientechnologies" % "orientdb-gremlin" % "3.0.0m2"
    "com.orientechnologies" % "orientdb-jdbc" % "2.2.12"
  ))

// Adds additional packages into Twirl
// TwirlKeys.templateImports += "com.codacy.controllers._"

// Adds additional packages into conf/routes
// play.sbt.routes.RoutesKeys.routesImport += "di.binders._"

scalacOptions ++= Seq(
  "-deprecation",
  "-encoding", "UTF-8",
  "-feature",
  "-language:existentials",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-unchecked",
  //    "-Xfatal-warnings",
  "-Xlint",
  "-Xlint:missing-interpolator",
  "-Yno-adapted-args",
  "-Ywarn-dead-code",
  "-Ywarn-numeric-widen",
  "-Ywarn-unused",
  "-Xfuture"
)
