////////////////////////////////////////////////////////////////////////////////
// Argon Design Ltd. Project P8009 Alogic
// Copyright (c) 2017 Argon Design Ltd. All rights reserved.
//
// Module : Scala Alogic Compiler
// Author : Peter de Rivaz/Geza Lore
//
// DESCRIPTION:
//
//
// This file is covered by the BSD (with attribution) license.
// See the LICENSE file for the precise wording of the license.
////////////////////////////////////////////////////////////////////////////////

////////////////////////////////////////////////////////////////////////////////
// About the project
////////////////////////////////////////////////////////////////////////////////

name := "alogic"

organization := "com.argondesign"

////////////////////////////////////////////////////////////////////////////////
// Scala compiler
////////////////////////////////////////////////////////////////////////////////

scalaVersion := "2.13.1"

scalacOptions ++= Seq("-feature",
                      "-explaintypes",
                      "-unchecked",
                      "-Xlint:_")

////////////////////////////////////////////////////////////////////////////////
// Library dependencies
////////////////////////////////////////////////////////////////////////////////

libraryDependencies += "org.rogach" %% "scallop" % "3.3.1"

libraryDependencies +=
  "org.scala-lang.modules" %% "scala-parallel-collections" % "0.2.0"

////////////////////////////////////////////////////////////////////////////////
// Testing dependencies
////////////////////////////////////////////////////////////////////////////////

libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.8" % "test"

logBuffered in Test := false

testOptions in Test += Tests.Argument("-oD") // Add F for full stack traces

////////////////////////////////////////////////////////////////////////////////
// Style check
////////////////////////////////////////////////////////////////////////////////

////////////////////////////////////////////////////////////////////////////////
// Antlr4 plugin
////////////////////////////////////////////////////////////////////////////////

enablePlugins(Antlr4Plugin)

antlr4Version in Antlr4 := "4.7.1"

antlr4PackageName in Antlr4 := Some("com.argondesign.alogic.antlr")

antlr4GenListener in Antlr4 := false

antlr4GenVisitor in Antlr4 := true

////////////////////////////////////////////////////////////////////////////////
// SBT native packager
////////////////////////////////////////////////////////////////////////////////

enablePlugins(JavaAppPackaging)

// stage := (stage dependsOn (test in Test)).value

bashScriptExtraDefines += """
# Pass a secret option if stderr is tty, but only if not asking for
# --help or --version
if [[ "$*" != "-h" && "$*" != "--help" ]] && \
   [[ "$*" != "-v" && "$*" != "--version" ]] && \
   [[ -t 2 ]]; then
  stderrisatty="--stderrisatty"
fi

if [[ "$*" == "--compiler-deps" ]]; then
  readlink -f "$0"
  echo "$app_classpath" | tr ":" "\n"
  exit 0
fi

# Prepend '--' to the command line arguments. This in fact causes the wrapper
# to not consume any arguments, in particular -D options
set -- -- ${stderrisatty} "$@"
"""

////////////////////////////////////////////////////////////////////////////////
// SBT git
////////////////////////////////////////////////////////////////////////////////

enablePlugins(GitVersioning)

git.useGitDescribe := true

////////////////////////////////////////////////////////////////////////////////
// SBT buildinfo
////////////////////////////////////////////////////////////////////////////////

enablePlugins(BuildInfoPlugin)

buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion)

buildInfoPackage := "com.argondesign.alogic"
