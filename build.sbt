name := "zio-pool"

version := "0.1"

scalaVersion := "2.13.7"

lazy val zioVersion = "1.0.12"
libraryDependencies ++= Seq(
  "dev.zio" %% "zio" % zioVersion,
  "dev.zio" %% "zio-test" % zioVersion % "test",
  "dev.zio" %% "zio-test-sbt" % zioVersion % "test"
)

Test / testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
Global / onChangedBuildSource := ReloadOnSourceChanges