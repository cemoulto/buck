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

package com.facebook.buck.go;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.HashedFileTool;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.CharMatcher;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.Map;

public class GoBuckConfig {

  private static final String SECTION = "go";
  private static final Path DEFAULT_GO_TOOL = Paths.get("go");

  private final BuckConfig delegate;

  private Supplier<Path> goRootSupplier;
  private Supplier<Path> goToolDirSupplier;

  private Supplier<GoPlatformFlavorDomain> platformFlavorDomain;
  private Supplier<GoPlatform> defaultPlatform;

  public GoBuckConfig(
      final BuckConfig delegate,
      final ProcessExecutor processExecutor,
      final FlavorDomain<CxxPlatform> cxxPlatforms) {
    this.delegate = delegate;

    goRootSupplier = Suppliers.memoize(
        new Supplier<Path>() {
          @Override
          public Path get() {
            Optional<Path> configValue = delegate.getPath(SECTION, "root");
            if (configValue.isPresent()) {
              return configValue.get();
            }

            return Paths.get(getGoEnvFromTool(processExecutor, "GOROOT"));
          }
        });

    goToolDirSupplier = Suppliers.memoize(
        new Supplier<Path>() {
          @Override
          public Path get() {
            return Paths.get(getGoEnvFromTool(processExecutor, "GOTOOLDIR"));
          }
        });

    platformFlavorDomain = Suppliers.memoize(new Supplier<GoPlatformFlavorDomain>() {
      @Override
      public GoPlatformFlavorDomain get() {
        // TODO(mikekap): Allow adding goos/goarch values from config.
        return new GoPlatformFlavorDomain(
            delegate.getPlatform(),
            delegate.getArchitecture(),
            cxxPlatforms);
      }
    });

    defaultPlatform = Suppliers.memoize(new Supplier<GoPlatform>() {
      @Override
      public GoPlatform get() {
        Optional<String> configValue = delegate.getValue(SECTION, "default_platform");
        Optional<GoPlatform> platform;
        if (configValue.isPresent()) {
          platform = platformFlavorDomain.get().getValue(
              ImmutableFlavor.of(configValue.get()));
          if (!platform.isPresent()) {
            throw new HumanReadableException(
                "Bad go platform value for %s.default_platform = %s", SECTION, configValue);
          }
        } else {
          platform = platformFlavorDomain.get().getValue(
              delegate.getPlatform(), delegate.getArchitecture());
          if (!platform.isPresent()) {
            throw new HumanReadableException(
                "Couldn't determine default go platform for %s %s",
                delegate.getPlatform(), delegate.getArchitecture());
          }
        }

        return platform.get();
      }
    });
  }

  GoPlatformFlavorDomain getPlatformFlavorDomain() {
    return platformFlavorDomain.get();
  }

  GoPlatform getDefaultPlatform() {
    return defaultPlatform.get();
  }

  Tool getCompiler() {
    return getGoTool("compiler", "compile", "compiler_flags");
  }
  Tool getAssembler() {
    return getGoTool("assembler", "asm", "asm_flags");
  }
  Tool getPacker() {
    return getGoTool("packer", "pack", "");
  }
  Tool getLinker() {
    return getGoTool("linker", "link", "linker_flags");
  }

  Path getDefaultPackageName(BuildTarget target) {
    Path prefix = Paths.get(delegate.getValue(SECTION, "prefix").or(""));
    return prefix.resolve(target.getBasePath());
  }

  ImmutableList<Path> getVendorPaths() {
    Optional<ImmutableList<String>> vendorPaths =
        delegate.getOptionalListWithoutComments(SECTION, "vendor_path", ':');

    if (vendorPaths.isPresent()) {
      return FluentIterable
          .from(vendorPaths.get())
          .transform(MorePaths.TO_PATH).toList();
    }
    return ImmutableList.of();
  }

  Optional<Tool> getGoTestMainGenerator(BuildRuleResolver resolver) {
    return delegate.getTool(SECTION, "test_main_gen", resolver);
  }

  ImmutableList<Path> getAssemblerIncludeDirs() {
    // TODO(mikekap): Allow customizing this via config.
    return ImmutableList.of(goRootSupplier.get().resolve("pkg").resolve("include"));
  }

  private Tool getGoTool(
      final String configName, final String toolName, final String extraFlagsConfigKey) {
    Optional<Path> toolPath = delegate.getPath(SECTION, configName);
    if (!toolPath.isPresent()) {
      toolPath = Optional.of(goToolDirSupplier.get().resolve(toolName));
    }

    CommandTool.Builder builder = new CommandTool.Builder(new HashedFileTool(toolPath.get()));
    if (!extraFlagsConfigKey.isEmpty()) {
      for (String arg : getFlags(extraFlagsConfigKey)) {
        builder.addArg(arg);
      }
    }
    builder.addEnv("GOROOT", goRootSupplier.get().toString());
    return builder.build();
  }

  private ImmutableList<String> getFlags(String key) {
    return ImmutableList.copyOf(
        Splitter.on(" ").omitEmptyStrings().split(
            delegate.getValue(SECTION, key).or("")));
  }

  private Path getGoToolPath() {
    Optional<Path> goTool = delegate.getPath(SECTION, "tool");
    if (goTool.isPresent()) {
      return goTool.get();
    }

    // Try resolving it via the go root config var. We can't use goRootSupplier here since that
    // would create a recursion.
    Optional<Path> goRoot = delegate.getPath(SECTION, "root");
    if (goRoot.isPresent()) {
      return goRoot.get().resolve("bin").resolve("go");
    }

    return new ExecutableFinder().getExecutable(DEFAULT_GO_TOOL, delegate.getEnvironment());
  }

  private String getGoEnvFromTool(ProcessExecutor processExecutor, String env) {
    Path goTool = getGoToolPath();
    Optional<Map<String, String>> goRootEnv = delegate.getPath(SECTION, "root").transform(
        new Function<Path, Map<String, String>>() {
          @Override
          public Map<String, String> apply(Path input) {
            return ImmutableMap.of("GOROOT", input.toString());
          }
        });
    try {
      ProcessExecutor.Result goToolResult = processExecutor.launchAndExecute(
          ProcessExecutorParams.builder().addCommand(
              goTool.toString(), "env", env).setEnvironment(goRootEnv).build(),
          EnumSet.of(ProcessExecutor.Option.EXPECTING_STD_OUT),
                    /* stdin */ Optional.<String>absent(),
                    /* timeOutMs */ Optional.<Long>absent(),
                    /* timeoutHandler */ Optional.<Function<Process, Void>>absent());
      if (goToolResult.getExitCode() == 0) {
        return CharMatcher.whitespace().trimFrom(goToolResult.getStdout().get());
      } else {
        throw new HumanReadableException(goToolResult.getStderr().get());
      }
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    } catch (IOException e) {
      throw new HumanReadableException(
          e,
          "Could not run \"%s env %s\": %s",
          env, goTool);
    }
  }
}
