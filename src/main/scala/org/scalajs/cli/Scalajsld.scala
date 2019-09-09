/*                     __                                               *\
**     ________ ___   / /  ___      __ ____  Scala.js CLI               **
**    / __/ __// _ | / /  / _ | __ / // __/  (c) 2013-2014, LAMP/EPFL   **
**  __\ \/ /__/ __ |/ /__/ __ |/_// /_\ \    http://scala-js.org/       **
** /____/\___/_/ |_/____/_/ | |__/ /____/                               **
**                          |/____/                                     **
\*                                                                      */


package org.scalajs.cli

import org.scalajs.ir.ScalaJSVersions

import org.scalajs.logging._

import org.scalajs.linker._
import org.scalajs.linker.irio._

import CheckedBehavior.Compliant

import scala.collection.immutable.Seq

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.Implicits.global

import java.io.File
import java.net.URI

object Scalajsld {

  private case class Options(
      cp: Seq[File] = Seq.empty,
      moduleInitializers: Seq[ModuleInitializer] = Seq.empty,
      output: File = null,
      semantics: Semantics = Semantics.Defaults,
      esFeatures: ESFeatures = ESFeatures.Defaults,
      moduleKind: ModuleKind = ModuleKind.NoModule,
      noOpt: Boolean = false,
      fullOpt: Boolean = false,
      prettyPrint: Boolean = false,
      sourceMap: Boolean = false,
      relativizeSourceMap: Option[URI] = None,
      checkIR: Boolean = false,
      stdLib: Option[File] = None,
      logLevel: Level = Level.Info
  )

  private implicit object MainMethodRead extends scopt.Read[ModuleInitializer] {
    val arity = 1
    val reads = { (s: String) =>
      val lastDot = s.lastIndexOf('.')
      if (lastDot < 0)
        throw new IllegalArgumentException(s"$s is not a valid main method")
      ModuleInitializer.mainMethodWithArgs(s.substring(0, lastDot),
          s.substring(lastDot + 1))
    }
  }

  private implicit object ModuleKindRead extends scopt.Read[ModuleKind] {
    val arity = 1
    val reads = { (s: String) =>
      ModuleKind.All.find(_.toString() == s).getOrElse(
          throw new IllegalArgumentException(s"$s is not a valid module kind"))
    }
  }

  def main(args: Array[String]): Unit = {
    val parser = new scopt.OptionParser[Options]("scalajsld") {
      head("scalajsld", ScalaJSVersions.current)
      arg[File]("<value> ...")
        .unbounded()
        .action { (x, c) => c.copy(cp = c.cp :+ x) }
        .text("Entries of Scala.js classpath to link")
      opt[ModuleInitializer]("mainMethod")
        .valueName("<full.name.Object.main>")
        .abbr("mm")
        .unbounded()
        .action { (x, c) => c.copy(moduleInitializers = c.moduleInitializers :+ x) }
        .text("Execute the specified main(Array[String]) method on startup")
      opt[File]('o', "output")
        .valueName("<file>")
        .required()
        .action { (x, c) => c.copy(output = x) }
        .text("Output file of linker (required)")
      opt[Unit]('f', "fastOpt")
        .action { (_, c) => c.copy(noOpt = false, fullOpt = false) }
        .text("Optimize code (this is the default)")
      opt[Unit]('n', "noOpt")
        .action { (_, c) => c.copy(noOpt = true, fullOpt = false) }
        .text("Don't optimize code")
      opt[Unit]('u', "fullOpt")
        .action { (_, c) => c.copy(noOpt = false, fullOpt = true) }
        .text("Fully optimize code (uses Google Closure Compiler)")
      opt[Unit]('p', "prettyPrint")
        .action { (_, c) => c.copy(prettyPrint = true) }
        .text("Pretty print full opted code (meaningful with -u)")
      opt[Unit]('s', "sourceMap")
        .action { (_, c) => c.copy(sourceMap = true) }
        .text("Produce a source map for the produced code")
      opt[Unit]("compliantAsInstanceOfs")
        .action { (_, c) => c.copy(semantics =
          c.semantics.withAsInstanceOfs(Compliant))
        }
        .text("Use compliant asInstanceOfs")
      opt[Unit]("es2015")
        .action { (_, c) => c.copy(esFeatures = c.esFeatures.withUseECMAScript2015(true)) }
        .text("Use ECMAScript 2015")
      opt[ModuleKind]('k', "moduleKind")
        .action { (kind, c) => c.copy(moduleKind = kind) }
        .text("Module kind " + ModuleKind.All.mkString("(", ", ", ")"))
      opt[Unit]('c', "checkIR")
        .action { (_, c) => c.copy(checkIR = true) }
        .text("Check IR before optimizing")
      opt[File]('r', "relativizeSourceMap")
        .valueName("<path>")
        .action { (x, c) => c.copy(relativizeSourceMap = Some(x.toURI)) }
        .text("Relativize source map with respect to given path (meaningful with -s)")
      opt[Unit]("noStdlib")
        .action { (_, c) => c.copy(stdLib = None) }
        .text("Don't automatically include Scala.js standard library")
      opt[File]("stdlib")
        .valueName("<scala.js stdlib jar>")
        .hidden()
        .action { (x, c) => c.copy(stdLib = Some(x)) }
        .text("Location of Scala.js standard libarary. This is set by the " +
            "runner script and automatically prepended to the classpath. " +
            "Use -n to not include it.")
      opt[Unit]('d', "debug")
        .action { (_, c) => c.copy(logLevel = Level.Debug) }
        .text("Debug mode: Show full log")
      opt[Unit]('q', "quiet")
        .action { (_, c) => c.copy(logLevel = Level.Warn) }
        .text("Only show warnings & errors")
      opt[Unit]("really-quiet")
        .abbr("qq")
        .action { (_, c) => c.copy(logLevel = Level.Error) }
        .text("Only show errors")
      version("version")
        .abbr("v")
        .text("Show scalajsld version")
      help("help")
        .abbr("h")
        .text("prints this usage text")

      override def showUsageOnError = true
    }

    for (options <- parser.parse(args, Options())) {
      val classpath = (options.stdLib.toList ++ options.cp).map(_.toPath())
      val moduleInitializers = options.moduleInitializers

      val semantics =
        if (options.fullOpt) options.semantics.optimized
        else options.semantics

      val config = StandardLinker.Config()
        .withSemantics(semantics)
        .withModuleKind(options.moduleKind)
        .withESFeatures(options.esFeatures)
        .withCheckIR(options.checkIR)
        .withOptimizer(!options.noOpt)
        .withParallel(true)
        .withSourceMap(options.sourceMap)
        .withRelativizeSourceMapBase(options.relativizeSourceMap)
        .withClosureCompiler(options.fullOpt)
        .withPrettyPrint(options.prettyPrint)
        .withBatchMode(true)

      val linker = StandardLinker(config)
      val logger = new ScalaConsoleLogger(options.logLevel)
      val outFile = new WritableFileVirtualBinaryFile(options.output.toPath())
      val output = LinkerOutput(outFile)

      val result = FileScalaJSIRContainer
        .fromClasspath(classpath)
        .flatMap(containers => Future.traverse(containers)(_.sjsirFiles).map(_.flatten))
        .flatMap(linker.link(_, moduleInitializers, output, logger))
      Await.result(result, Duration.Inf)
    }
  }
}
