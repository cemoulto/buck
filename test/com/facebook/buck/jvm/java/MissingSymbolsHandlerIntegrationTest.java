/*
 * Copyright 2014-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.jvm.java;

import static com.facebook.buck.jvm.java.JavaCompilationConstants.DEFAULT_JAVAC_OPTIONS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.config.Config;
import com.facebook.buck.cli.MissingSymbolsHandler;
import com.facebook.buck.config.Configs;
import com.facebook.buck.config.RawConfig;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.event.MissingSymbolEvent;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.DefaultKnownBuildRuleTypes;
import com.facebook.buck.rules.Description;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Paths;

public class MissingSymbolsHandlerIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder temporaryFolder = new DebuggableTemporaryFolder();

  @Test
  public void shouldFindNeededDependenciesFromSymbols() throws IOException, InterruptedException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "symbol_finder", temporaryFolder);
    workspace.setUp();

    ProjectFilesystem projectFilesystem = new ProjectFilesystem(temporaryFolder.getRootPath());
    ImmutableMap<String, String> environment = ImmutableMap.copyOf(System.getenv());

    Config rawConfig = Configs.createDefaultConfig(projectFilesystem.getRootPath(), RawConfig.of());
    BuckConfig config = new BuckConfig(
        rawConfig,
        projectFilesystem,
        Architecture.detect(),
        Platform.detect(),
        environment);
    ImmutableSet<Description<?>> allDescriptions =
        DefaultKnownBuildRuleTypes
        .getDefaultKnownBuildRuleTypes(projectFilesystem, environment)
        .getAllDescriptions();
    BuckEventBus buckEventBus = BuckEventBusFactory.newInstance();

    MissingSymbolsHandler missingSymbolsHandler = MissingSymbolsHandler.create(
        projectFilesystem,
        allDescriptions,
        config,
        buckEventBus,
        new TestConsole(),
        DEFAULT_JAVAC_OPTIONS,
        environment);

    MissingSymbolEvent missingSymbolEvent = MissingSymbolEvent.create(
        BuildTargetFactory.newInstance(workspace.getDestPath(), "//java/com/example/b:b"),
        "com.example.a.A",
        MissingSymbolEvent.SymbolType.Java);

    ImmutableSetMultimap<BuildTarget, BuildTarget> neededDeps =
        missingSymbolsHandler.getNeededDependencies(ImmutableList.of(missingSymbolEvent));

    assertEquals(
        "MissingSymbolsHandler failed to find the needed dependency.",
        neededDeps,
        ImmutableSetMultimap.of(
            BuildTargetFactory.newInstance(workspace.getDestPath(), "//java/com/example/b:b"),
            BuildTargetFactory.newInstance(workspace.getDestPath(), "//java/com/example/a:a")));
  }

  @Test
  public void shouldPrintNeededSymbolsFromBuild() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "symbol_finder", temporaryFolder);
    workspace.setUp();

    ProjectWorkspace.ProcessResult processResult = workspace.runBuckBuild("//java/com/example/b:b");
    processResult.assertFailure("Build with missing dependencies should fail.");

    String expectedDependencyOutput = String.format(
        "%s (:b) is missing deps:\n" +
        "    ':moreb',\n" +
        "    '//java/com/example/a:a',\n",
        Paths.get("java/com/example/b/BUCK"));

    assertThat(
        "Output should describe the missing dependency.",
        processResult.getStderr(),
        containsString(expectedDependencyOutput));
  }

  @Test
  public void shouldPrintNeededSymbolsFromTest() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "symbol_finder", temporaryFolder);
    workspace.setUp();

    ProjectWorkspace.ProcessResult processResult = workspace.runBuckCommand(
        "test",
        "//java/com/example/b:test");
    processResult.assertFailure("Test with missing dependencies should fail.");

    String expectedDependencyOutput = String.format(
        "%s (:test) is missing deps:\n" +
        "    ':moreb',\n" +
        "    '//java/com/example/a:a',\n",
        Paths.get("java/com/example/b/BUCK"));

    assertThat(
        "Output should describe the missing dependency.",
        processResult.getStderr(),
        containsString(expectedDependencyOutput));
  }
}
