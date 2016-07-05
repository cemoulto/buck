/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.parser;


import static com.facebook.buck.parser.ParserConfig.DEFAULT_BUILD_FILE_NAME;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.bser.BserSerializer;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.json.ProjectBuildFileParser;
import com.facebook.buck.json.ProjectBuildFileParserFactory;
import com.facebook.buck.json.ProjectBuildFileParserOptions;
import com.facebook.buck.python.PythonBuckConfig;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.ConstructorArgMarshaller;
import com.facebook.buck.rules.KnownBuildRuleTypes;
import com.facebook.buck.rules.TestCellBuilder;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.timing.FakeClock;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.FakeProcess;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.eventbus.Subscribe;

import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ProjectBuildFileParserTest {

  private Cell cell;

  @Before
  public void createCell() throws IOException, InterruptedException {
    cell = new TestCellBuilder().build();
  }

  private static FakeProcess fakeProcessWithBserOutput(
      int returnCode,
      List<Object> values,
      Optional<List<Object>> diagnostics,
      Optional<String> stdout) {
    BserSerializer bserSerializer = new BserSerializer();
    ByteBuffer buffer = ByteBuffer.allocate(512).order(ByteOrder.nativeOrder());
    try {
      Map<String, Object> outputToSerialize = new LinkedHashMap<>();
      outputToSerialize.put("values", values);
      if (diagnostics.isPresent()) {
        outputToSerialize.put("diagnostics", diagnostics.get());
      }
      buffer = bserSerializer.serializeToBuffer(outputToSerialize, buffer);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    buffer.flip();
    return new FakeProcess(
        returnCode,
        new ByteArrayOutputStream(),
        new ByteArrayInputStream(buffer.array()),
        new ByteArrayInputStream(stdout.or("").getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  public void whenSubprocessReturnsSuccessThenProjectBuildFileParserClosesCleanly()
      throws IOException, BuildFileParseException, InterruptedException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(cell.getRoot(), cell.getKnownBuildRuleTypes());
    try (ProjectBuildFileParser buildFileParser =
             buildFileParserFactory.createNoopParserThatAlwaysReturnsSuccess()) {
      buildFileParser.initIfNeeded();
      // close() is called implicitly at the end of this block. It must not throw.
    }
  }

  @Test(expected = BuildFileParseException.class)
  public void whenSubprocessReturnsFailureThenProjectBuildFileParserThrowsOnClose()
      throws IOException, BuildFileParseException, InterruptedException {
    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(cell.getRoot(), cell.getKnownBuildRuleTypes());
    try (ProjectBuildFileParser buildFileParser =
             buildFileParserFactory.createNoopParserThatAlwaysReturnsError()) {
      buildFileParser.initIfNeeded();
      // close() is called implicitly at the end of this block. It must throw.
    }
  }

  @Test
  public void whenSubprocessPrintsWarningToStderrThenConsoleEventPublished()
      throws IOException, BuildFileParseException, InterruptedException {
    // This test depends on unix utilities that don't exist on Windows.
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(cell.getRoot(), cell.getKnownBuildRuleTypes());
    BuckEventBus buckEventBus = BuckEventBusFactory.newInstance(new FakeClock(0));
    final List<ConsoleEvent> consoleEvents = new ArrayList<>();
    class EventListener {
      @Subscribe
      public void onConsoleEvent(ConsoleEvent consoleEvent) {
        consoleEvents.add(consoleEvent);
      }
    }
    EventListener eventListener = new EventListener();
    buckEventBus.register(eventListener);
    try (ProjectBuildFileParser buildFileParser =
             buildFileParserFactory.createNoopParserThatAlwaysReturnsSuccessAndPrintsToStderr(
                 buckEventBus)) {
      buildFileParser.initIfNeeded();
      buildFileParser.getAllRulesAndMetaRules(Paths.get("foo"));
    }
    assertThat(
        consoleEvents,
        Matchers.contains(
            Matchers.hasToString("Warning raised by BUCK file parser: Don't Panic!")));
  }

  @Test
  public void whenSubprocessReturnsWarningThenConsoleEventPublished()
      throws IOException, BuildFileParseException, InterruptedException {
    // This test depends on unix utilities that don't exist on Windows.
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(cell.getRoot(), cell.getKnownBuildRuleTypes());
    BuckEventBus buckEventBus = BuckEventBusFactory.newInstance(new FakeClock(0));
    final List<ConsoleEvent> consoleEvents = new ArrayList<>();
    class EventListener {
      @Subscribe
      public void onConsoleEvent(ConsoleEvent consoleEvent) {
        consoleEvents.add(consoleEvent);
      }
    }
    EventListener eventListener = new EventListener();
    buckEventBus.register(eventListener);
    try (ProjectBuildFileParser buildFileParser =
             buildFileParserFactory.createNoopParserThatAlwaysReturnsSuccessWithWarning(
                 buckEventBus, "This is a warning")) {
      buildFileParser.initIfNeeded();
      buildFileParser.getAllRulesAndMetaRules(Paths.get("foo"));
    }
    assertThat(
        consoleEvents,
        Matchers.contains(
            Matchers.hasToString("Warning raised by BUCK file parser: This is a warning")));
  }

  @Test
  public void whenSubprocessReturnsErrorThenConsoleEventPublished()
      throws IOException, BuildFileParseException, InterruptedException {
    // This test depends on unix utilities that don't exist on Windows.
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    TestProjectBuildFileParserFactory buildFileParserFactory =
        new TestProjectBuildFileParserFactory(cell.getRoot(), cell.getKnownBuildRuleTypes());
    BuckEventBus buckEventBus = BuckEventBusFactory.newInstance(new FakeClock(0));
    final List<ConsoleEvent> consoleEvents = new ArrayList<>();
    class EventListener {
      @Subscribe
      public void onConsoleEvent(ConsoleEvent consoleEvent) {
        consoleEvents.add(consoleEvent);
      }
    }
    EventListener eventListener = new EventListener();
    buckEventBus.register(eventListener);
    try (ProjectBuildFileParser buildFileParser =
             buildFileParserFactory.createNoopParserThatAlwaysReturnsSuccessWithError(
                 buckEventBus, "This is an error")) {
      buildFileParser.initIfNeeded();
      buildFileParser.getAllRulesAndMetaRules(Paths.get("foo"));
    }
    assertThat(
        consoleEvents,
        Matchers.contains(
            Matchers.hasToString("Error raised by BUCK file parser: This is an error")));
  }

  /**
   * ProjectBuildFileParser test double which counts the number of times rules are parsed to test
   * caching logic in Parser.
   */
  private static class TestProjectBuildFileParserFactory implements ProjectBuildFileParserFactory {
    private final Path projectRoot;
    private final KnownBuildRuleTypes buildRuleTypes;

    public TestProjectBuildFileParserFactory(
        Path projectRoot,
        KnownBuildRuleTypes buildRuleTypes) {
      this.projectRoot = projectRoot;
      this.buildRuleTypes = buildRuleTypes;
    }

    @Override
    public ProjectBuildFileParser createParser(
        ConstructorArgMarshaller marshaller,
        Console console,
        ImmutableMap<String, String> environment,
        BuckEventBus buckEventBus,
        boolean ignoreBuckAutodepsFiles) {
      PythonBuckConfig config = new PythonBuckConfig(
          FakeBuckConfig.builder().setEnvironment(environment).build(),
          new ExecutableFinder());
      return new TestProjectBuildFileParser(
          config.getPythonInterpreter(),
          new ProcessExecutor(console));
    }

    public ProjectBuildFileParser createNoopParserThatAlwaysReturnsError() {
      return new TestProjectBuildFileParser(
          "fake-python",
          new FakeProcessExecutor(
              new Function<ProcessExecutorParams, FakeProcess>() {
                @Override
                public FakeProcess apply(ProcessExecutorParams params) {
                  return fakeProcessWithBserOutput(
                      1,
                      ImmutableList.of(),
                      Optional.<List<Object>>absent(),
                      Optional.<String>absent());
                }
              },
              new TestConsole()));
    }

    public ProjectBuildFileParser createNoopParserThatAlwaysReturnsSuccess() {
      return new TestProjectBuildFileParser(
          "fake-python",
          new FakeProcessExecutor(
              new Function<ProcessExecutorParams, FakeProcess>() {
                @Override
                public FakeProcess apply(ProcessExecutorParams params) {
                  return fakeProcessWithBserOutput(
                      0,
                      ImmutableList.of(),
                      Optional.<List<Object>>absent(),
                      Optional.<String>absent());
                }
              },
              new TestConsole()));
    }

    public ProjectBuildFileParser createNoopParserThatAlwaysReturnsSuccessAndPrintsToStderr(
        BuckEventBus buckEventBus) {
      return new TestProjectBuildFileParser(
          "fake-python",
          new FakeProcessExecutor(
              new Function<ProcessExecutorParams, FakeProcess>() {
                @Override
                public FakeProcess apply(ProcessExecutorParams params) {
                  return fakeProcessWithBserOutput(
                      0,
                      ImmutableList.of(),
                      Optional.<List<Object>>absent(),
                      Optional.of("Don't Panic!"));
                }
              },
              new TestConsole()),
          buckEventBus);
    }

    public ProjectBuildFileParser createNoopParserThatAlwaysReturnsSuccessWithWarning(
        BuckEventBus buckEventBus,
        final String warning) {
      return new TestProjectBuildFileParser(
          "fake-python",
          new FakeProcessExecutor(
              new Function<ProcessExecutorParams, FakeProcess>() {
                @Override
                public FakeProcess apply(ProcessExecutorParams params) {
                  return fakeProcessWithBserOutput(
                      0,
                      ImmutableList.of(),
                      Optional.<List<Object>>of(
                          ImmutableList.<Object>of(
                              ImmutableMap.of("level", "warning", "message", warning))),
                      Optional.<String>absent());
                }
              },
              new TestConsole()),
          buckEventBus);
    }

    public ProjectBuildFileParser createNoopParserThatAlwaysReturnsSuccessWithError(
        BuckEventBus buckEventBus,
        final String error) {
      return new TestProjectBuildFileParser(
          "fake-python",
          new FakeProcessExecutor(
              new Function<ProcessExecutorParams, FakeProcess>() {
                @Override
                public FakeProcess apply(ProcessExecutorParams params) {
                  return fakeProcessWithBserOutput(
                      0,
                      ImmutableList.of(),
                      Optional.<List<Object>>of(
                          ImmutableList.<Object>of(
                              ImmutableMap.of("level", "error", "message", error))),
                      Optional.<String>absent());
                }
              },
              new TestConsole()),
          buckEventBus);
    }

    private class TestProjectBuildFileParser extends ProjectBuildFileParser {
      public TestProjectBuildFileParser(
          String pythonInterpreter,
          ProcessExecutor processExecutor) {
        this(pythonInterpreter, processExecutor, BuckEventBusFactory.newInstance());
      }
      public TestProjectBuildFileParser(
          String pythonInterpreter,
          ProcessExecutor processExecutor,
          BuckEventBus buckEventBus) {
        super(
            ProjectBuildFileParserOptions.builder()
                .setProjectRoot(projectRoot)
                .setPythonInterpreter(pythonInterpreter)
                .setAllowEmptyGlobs(ParserConfig.DEFAULT_ALLOW_EMPTY_GLOBS)
                .setBuildFileName(DEFAULT_BUILD_FILE_NAME)
                .setDefaultIncludes(ImmutableSet.of("//java/com/facebook/defaultIncludeFile"))
                .setDescriptions(buildRuleTypes.getAllDescriptions())
                .build(),
            new ConstructorArgMarshaller(new DefaultTypeCoercerFactory(
                ObjectMappers.newDefaultInstance())),
            ImmutableMap.<String, String>of(),
            ImmutableMap.<String, ImmutableMap<String, String>>of(),
            buckEventBus,
            processExecutor,
            /* ignoreBuckAutodepsFiles */ false);
      }
    }
  }
}
