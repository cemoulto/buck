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

package com.facebook.buck.apple;

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorConvertible;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Function;
import com.google.common.base.Optional;

import java.nio.file.Path;
import java.util.Set;

import org.immutables.value.Value;

/**
 * Adds Apple-specific tools to {@link CxxPlatform}.
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractAppleCxxPlatform implements FlavorConvertible {

  public static final Function<AppleCxxPlatform, AppleSdkPaths> TO_APPLE_SDK_PATHS =
      new Function<AppleCxxPlatform, AppleSdkPaths>() {
        @Override
        public AppleSdkPaths apply(AppleCxxPlatform platform) {
          return platform.getAppleSdkPaths();
        }
      };

  public abstract CxxPlatform getCxxPlatform();

  public abstract AppleSdk getAppleSdk();

  public abstract AppleSdkPaths getAppleSdkPaths();

  public abstract Optional<String> getBuildVersion();
  public abstract String getMinVersion();
  public abstract Tool getActool();
  public abstract Tool getIbtool();
  public abstract Tool getXctest();
  public abstract Optional<Tool> getOtest();
  public abstract Tool getDsymutil();
  public abstract Tool getLipo();
  public abstract Optional<Path> getStubBinary();
  public abstract Tool getLldb();
  public abstract Optional<Tool> getCodesignAllocate();
  public abstract Optional<Tool> getSwift();
  public abstract Optional<Tool> getSwiftStdlibTool();
  public abstract Set<Path> getSwiftRuntimePaths();
  public abstract Set<Path> getSwiftStaticRuntimePaths();

  // Short Xcode version code, e.g. 0721
  public abstract Optional<String> getXcodeVersion();

  // Xcode build identifier, e.g. 7C1002
  public abstract Optional<String> getXcodeBuildVersion();

  @Override
  public Flavor getFlavor() {
    return getCxxPlatform().getFlavor();
  }
}
