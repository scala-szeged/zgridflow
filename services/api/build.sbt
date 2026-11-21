ThisBuild / scalaVersion := "3.10.1"
ThisBuild / version := "0.1.0"
ThisBuild / organization := "dev.zgridflow"

lazy val root = (project in file("."))
  .settings(
    name := "zgridflow-api",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % "2.1.27",
      "dev.zio" %% "zio-http" % "3.11.6",
      "dev.zio" %% "zio-json" % "1.1.3",
      "software.amazon.awssdk" % "dynamodb" % "2.59.7",
      "software.amazon.awssdk" % "url-connection-client" % "2.59.7",
      "org.apache.kafka" % "kafka-clients" % "4.4.0",
      "org.slf4j" % "slf4j-simple" % "2.0.17",
      "dev.zio" %% "zio-test" % "2.1.27" % Test,
      "dev.zio" %% "zio-test-sbt" % "2.1.27" % Test
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    assembly / mainClass := Some("dev.zgridflow.Main"),
    assembly / assemblyJarName := "zgridflow-api.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", _*) => MergeStrategy.concat
      case PathList("META-INF", _*) => MergeStrategy.discard
      case "module-info.class" => MergeStrategy.discard
      case "reference.conf" => MergeStrategy.concat
      case _ => MergeStrategy.first
    }
  )
