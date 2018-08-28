// *****************************************************************************
// Projects
// *****************************************************************************

lazy val `effective-actors` =
  project
    .in(file("."))
    .enablePlugins(AutomateHeaderPlugin)
    .settings(settings)
    .settings(
      libraryDependencies ++= Seq(
        library.cats,
        library.`cats-effect`,
        library.fs2,
        library.scalaCheck % Test,
        library.utest % Test
      )
    )

// *****************************************************************************
// Library dependencies
// *****************************************************************************

lazy val library =
  new {

    object Version {
      val scalaCheck = "1.14.0"
      val utest = "0.6.4"
      val `cats-effect` = "1.0.0-RC3"
      val cats = "1.2.0"
      val fs2 = "1.0.0-M4"
    }

    val `cats-effect` = "org.typelevel" %% "cats-effect" % Version.`cats-effect`
    val fs2 = "co.fs2" %% "fs2-core" % Version.fs2
    val cats = "org.typelevel" %% "cats-core" % Version.cats
    val scalaCheck = "org.scalacheck" %% "scalacheck" % Version.scalaCheck
    val utest = "com.lihaoyi" %% "utest" % Version.utest
  }

// *****************************************************************************
// Settings
// *****************************************************************************

lazy val settings =
  commonSettings ++
    scalafmtSettings

lazy val commonSettings =
  Seq(
    // scalaVersion from .travis.yml via sbt-travisci
    // scalaVersion := "2.12.4",
    organization := "default",
    organizationName := "carlo",
    startYear := Some(2018),
    licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0")),
    scalacOptions ++= Seq(
      "-unchecked",
      "-deprecation",
      "-language:_",
      "-target:jvm-1.8",
      "-encoding", "UTF-8",
      "-Ypartial-unification",
      "-Ywarn-unused-import"
    ),
    Compile / unmanagedSourceDirectories := Seq((Compile / scalaSource).value),
    Test / unmanagedSourceDirectories := Seq((Test / scalaSource).value),
    testFrameworks += new TestFramework("utest.runner.Framework"),
    Compile / compile / wartremoverWarnings ++= Warts.unsafe,
    addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.2.4"),
    cancelable in Test := true

  )

lazy val scalafmtSettings =
  Seq(
    scalafmtOnCompile := true
  )
