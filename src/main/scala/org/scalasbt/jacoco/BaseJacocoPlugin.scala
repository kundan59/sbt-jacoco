/*
 * This file is part of sbt-jacoco.
 *
 * Copyright (c) Joachim Hofer & contributors
 * All rights reserved.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package org.scalasbt.jacoco

import java.io.File

import org.jacoco.core.runtime.{IRuntime, LoggerRuntime, RuntimeData}
import org.scalasbt.jacoco.build.BuildInfo
import sbt.Keys._
import sbt.plugins.JvmPlugin
import sbt.{Def, _}

private[jacoco] abstract class BaseJacocoPlugin extends AutoPlugin with JacocoKeys {
  protected implicit val runtimeData: RuntimeData = new RuntimeData()
  protected implicit val loggerRuntime: IRuntime = new LoggerRuntime()

  override def requires: Plugins = JvmPlugin

  protected def srcConfig: Configuration

  override def projectSettings: Seq[Setting[_]] =
    Seq(
      libraryDependencies ++= {
        if ((fork in srcConfig).value) {
          // config is set to fork - need to add the jacoco agent to the classpath so it can process instrumentation
          Seq("org.jacoco" % "org.jacoco.agent" % BuildInfo.jacocoVersion % Test classifier "runtime")
        } else {
          Nil
        }
      }
    ) ++ inConfig(srcConfig)(settingValues ++ taskValues)

  private def settingValues = Seq(
    jacocoDirectory := crossTarget.value / "jacoco",
    jacocoReportDirectory := jacocoDirectory.value / "report",
    jacocoSourceSettings := JacocoSourceSettings(),
    jacocoReportSettings := JacocoReportSettings(),
    jacocoAggregateReportSettings := JacocoReportSettings(title = "Jacoco Aggregate Coverage Report"),
    jacocoIncludes := Seq("*"),
    jacocoExcludes := Seq(),
    jacocoInstrumentedDirectory := jacocoDirectory.value / "instrumented-classes",
    jacocoDataFile := jacocoDataDirectory.value / "jacoco.exec",
    javaOptions in srcConfig ++= {
      val dest = jacocoDataFile.value
      if (fork.value) {
        Seq(
          s"-Djacoco-agent.destfile=${dest.absolutePath}"
        )
      } else {
        Nil
      }
    }
  )

  private def taskValues = Seq(
    jacoco := (jacocoReport dependsOn jacocoCheck).value,
    jacocoAggregate := (jacocoAggregateReport dependsOn submoduleCoverTasks).value,
    jacocoCheck := Def
      .task(SavingData.saveDataAction(jacocoDataFile.value, fork.value, streams.value))
      .dependsOn(test)
      .value,
    jacocoReport := Reporting.reportAction(
      jacocoReportDirectory.value,
      jacocoDataFile.value,
      jacocoReportSettings.value,
      coveredSources.value,
      classesToCover.value,
      jacocoSourceSettings.value,
      streams.value
    ),
    jacocoAggregateReport := Reporting.aggregateReportAction(
      jacocoReportDirectory.value / "aggregate",
      aggregateExecutionDataFiles.value,
      jacocoAggregateReportSettings.value,
      aggregateCoveredSources.value,
      aggregateClassesToCover.value,
      jacocoSourceSettings.value,
      streams.value
    ),
    clean := jacocoDirectory map (dir => if (dir.exists) IO delete dir.listFiles),
    fullClasspath := Instrumentation.instrumentAction(
      (products in Compile).value,
      (fullClasspath in srcConfig).value,
      jacocoInstrumentedDirectory.value,
      update.value,
      fork.value,
      streams.value),
    definedTests := (definedTests in srcConfig).value,
    definedTestNames := (definedTestNames in srcConfig).value
  )

  private def filterClassesToCover(classes: File, incl: Seq[String], excl: Seq[String]) = {
    val inclFilters = incl map GlobFilter.apply
    val exclFilters = excl map GlobFilter.apply

    (PathFinder(classes) ** new FileFilter {
      def accept(f: File): Boolean = IO.relativize(classes, f) match {
        case Some(file) if !f.isDirectory && file.endsWith(".class") =>
          val name = toClassName(file)
          inclFilters.exists(_ accept name) && !exclFilters.exists(_ accept name)
        case _ => false
      }
    }).get
  }

  private def toClassName(entry: String): String =
    entry.stripSuffix(ClassExt).replace('/', '.')

  private val ClassExt = ".class"

  protected lazy val submoduleSettingsTask: Def.Initialize[Task[(Seq[File], Option[File], Option[File])]] = Def.task {
    (classesToCover.value, (sourceDirectory in Compile).?.value, (jacocoDataFile in srcConfig).?.value)
  }

  protected lazy val submoduleSettings: Def.Initialize[Task[Seq[(Seq[File], Option[File], Option[File])]]] =
    submoduleSettingsTask.all(ScopeFilter(inAggregates(ThisProject), inConfigurations(Compile, srcConfig)))

  protected lazy val aggregateCoveredSources: Def.Initialize[Task[Seq[File]]] = Def.task {
    submoduleSettings.value.flatMap(_._2).distinct
  }

  protected lazy val classesToCover: Def.Initialize[Task[Seq[File]]] = Def.task {
    filterClassesToCover(
      (classDirectory in Compile).value,
      (jacocoIncludes in srcConfig).value,
      (jacocoExcludes in srcConfig).value)
  }

  protected lazy val aggregateClassesToCover: Def.Initialize[Task[Seq[File]]] = Def.task {
    submoduleSettings.value.flatMap(_._1).distinct
  }

  protected lazy val aggregateExecutionDataFiles: Def.Initialize[Task[Seq[File]]] = Def.task {
    submoduleSettings.value.flatMap(_._3).distinct
  }

  protected lazy val coveredSources: Def.Initialize[Task[Seq[File]]] = Def.task {
    (sourceDirectories in Compile).value
  }

  protected lazy val jacocoDataDirectory: Def.Initialize[File] = Def.setting {
    jacocoDirectory.value / "data"
  }

  protected lazy val submoduleCoverTasks: Def.Initialize[Task[Seq[Unit]]] = {
    (jacoco in srcConfig).all(ScopeFilter(inAggregates(ThisProject)))
  }
}