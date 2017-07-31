name := "orientdb-test"

version := "1.0"

scalaVersion := "2.11.11"

libraryDependencies ++= Seq(
  //  "com.orientechnologies" % "orientdb-core" % "2.2.24",
  //  "com.orientechnologies" % "orientdb-jdbc" % "2.2.24",
  //  "com.orientechnologies" % "orientdb-client" % "2.2.24",
  //  "com.orientechnologies" % "orientdb-graphdb" % "2.2.24",
  //  "com.tinkerpop.gremlin" % "gremlin-java" % "2.6.0",
  //  "com.tinkerpop.gremlin" % "blueprints-core" % "2.6.0"

  "com.michaelpollmeier" %% "gremlin-scala" % "3.2.5.2",
  "com.michaelpollmeier" % "orientdb-gremlin" % "3.2.3.0"
  //  "com.orientechnologies" % "orientdb-gremlin" % "3.0.0m2"
)
        