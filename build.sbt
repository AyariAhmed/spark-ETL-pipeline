ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.12.10"

lazy val root = (project in file("."))
  .settings(
    name := "de-challenge"
  )

val sparkVersion = "3.2.2"

resolvers ++= Seq(
  "bintray-spark-packages" at "https://dl.bintray.com/spark-packages/maven",
  "Typesafe Simple Repository" at "https://repo.typesafe.com/typesafe/simple/maven-releases",
  "MavenRepository" at "https://mvnrepository.com"
)


libraryDependencies ++= Seq(
  "org.apache.spark" %% "spark-core" % sparkVersion,
  "org.apache.spark" %% "spark-sql" % sparkVersion,
  // logging
  "org.apache.logging.log4j" % "log4j-api" % "2.19.0",
  "org.apache.logging.log4j" % "log4j-core" % "2.19.0",
  // cassandra connector
  "com.datastax.spark" %% "spark-cassandra-connector" % "3.2.0",
  "com.datastax.cassandra" % "cassandra-driver-core" % "4.0.0",
  "org.joda" % "joda-convert" % "2.2.2",
  "joda-time" % "joda-time" % "2.12.2"
)