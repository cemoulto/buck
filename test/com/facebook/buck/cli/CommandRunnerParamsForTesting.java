/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.cli;

import com.facebook.buck.android.AndroidBuckConfig;
import com.facebook.buck.android.AndroidDirectoryResolver;
import com.facebook.buck.android.FakeAndroidDirectoryResolver;
import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.artifact_cache.NoopArtifactCache;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.httpserver.WebServer;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.jvm.java.FakeJavaPackageFinder;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.rules.ActionGraphCache;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.ConstructorArgMarshaller;
import com.facebook.buck.rules.TestCellBuilder;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.timing.DefaultClock;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.ProcessManager;
import com.facebook.buck.util.TriState;
import com.facebook.buck.util.cache.NullFileHashCache;
import com.facebook.buck.util.environment.BuildEnvironmentDescription;
import com.facebook.buck.util.environment.Platform;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;

public class CommandRunnerParamsForTesting {

  public static final BuildEnvironmentDescription BUILD_ENVIRONMENT_DESCRIPTION =
      BuildEnvironmentDescription.builder()
          .setUser("test")
          .setHostname("test")
          .setOs("test")
          .setAvailableCores(1)
          .setSystemMemory(1024L)
          .setBuckDirty(TriState.FALSE)
          .setBuckCommit("test")
          .setJavaVersion("test")
          .setJsonProtocolVersion(1)
          .build();

  /** Utility class: do not instantiate. */
  private CommandRunnerParamsForTesting() {}

  public static CommandRunnerParams createCommandRunnerParamsForTesting(
      Console console,
      Cell cell,
      AndroidDirectoryResolver androidDirectoryResolver,
      ArtifactCache artifactCache,
      BuckEventBus eventBus,
      BuckConfig config,
      Platform platform,
      ImmutableMap<String, String> environment,
      JavaPackageFinder javaPackageFinder,
      ObjectMapper objectMapper,
      Optional<WebServer> webServer)
      throws IOException, InterruptedException {
    DefaultTypeCoercerFactory typeCoercerFactory = new DefaultTypeCoercerFactory(
        ObjectMappers.newDefaultInstance());
    return new CommandRunnerParams(
        console,
        new ByteArrayInputStream("".getBytes("UTF-8")),
        cell,
        Main.createAndroidPlatformTargetSupplier(
            androidDirectoryResolver,
            new AndroidBuckConfig(FakeBuckConfig.builder().build(), platform),
            eventBus),
        artifactCache,
        eventBus,
        new Parser(
            new ParserConfig(cell.getBuckConfig()),
            typeCoercerFactory, new ConstructorArgMarshaller(typeCoercerFactory)),
        platform,
        environment,
        javaPackageFinder,
        objectMapper,
        new DefaultClock(),
        Optional.<ProcessManager>absent(),
        webServer,
        config,
        new NullFileHashCache(),
        new HashMap<ExecutionContext.ExecutorPool, ListeningExecutorService>(),
        BUILD_ENVIRONMENT_DESCRIPTION,
        new ActionGraphCache());
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {

    private AndroidDirectoryResolver androidDirectoryResolver = new FakeAndroidDirectoryResolver();
    private ArtifactCache artifactCache = new NoopArtifactCache();
    private Console console = new TestConsole();
    private BuckConfig config = FakeBuckConfig.builder().build();
    private BuckEventBus eventBus = BuckEventBusFactory.newInstance();
    private Platform platform = Platform.detect();
    private ImmutableMap<String, String> environment = ImmutableMap.copyOf(System.getenv());
    private JavaPackageFinder javaPackageFinder = new FakeJavaPackageFinder();
    private ObjectMapper objectMapper = ObjectMappers.newDefaultInstance();
    private Optional<WebServer> webServer = Optional.absent();

    public CommandRunnerParams build()
        throws IOException, InterruptedException{
      return createCommandRunnerParamsForTesting(
          console,
          new TestCellBuilder().build(),
          androidDirectoryResolver,
          artifactCache,
          eventBus,
          config,
          platform,
          environment,
          javaPackageFinder,
          objectMapper,
          webServer);
    }

    public Builder setConsole(Console console) {
      this.console = console;
      return this;
    }

    public Builder setWebserver(Optional<WebServer> webServer) {
      this.webServer = webServer;
      return this;
    }

    public Builder setArtifactCache(ArtifactCache cache) {
      this.artifactCache = cache;
      return this;
    }

    public Builder setBuckConfig(BuckConfig buckConfig) {
      this.config = buckConfig;
      return this;
    }

  }
}
