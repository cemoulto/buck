/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import org.immutables.value.Value;

import java.nio.file.Path;

/**
 * Rule for generating an apple package via external script.
 */
public class ExternallyBuiltApplePackage extends Genrule implements RuleKeyAppendable {
  private ApplePackageConfigAndPlatformInfo packageConfigAndPlatformInfo;

  public ExternallyBuiltApplePackage(
      BuildRuleParams params,
      SourcePathResolver resolver,
      ApplePackageConfigAndPlatformInfo packageConfigAndPlatformInfo,
      SourcePath bundle) {
    super(
        params,
        resolver,
        ImmutableList.of(bundle),
        Optional.<Arg>of(new StringArg(packageConfigAndPlatformInfo.getConfig().getCommand())),
        Optional.<Arg>absent(),
        Optional.<Arg>absent(),
        params.getBuildTarget().getShortName() + "." +
            packageConfigAndPlatformInfo.getConfig().getExtension(),
        ImmutableSortedSet.<BuildTarget>of());
    this.packageConfigAndPlatformInfo = packageConfigAndPlatformInfo;
  }

  @Override
  protected void addEnvironmentVariables(
      ExecutionContext context,
      ImmutableMap.Builder<String, String> environmentVariablesBuilder) {
    super.addEnvironmentVariables(context, environmentVariablesBuilder);
    environmentVariablesBuilder.put(
        "PLATFORM_DIR",
        packageConfigAndPlatformInfo.getPlatformPath().toString());
  }

  @Override
  public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
    return builder
        .setReflectively("sdkVersion", packageConfigAndPlatformInfo.getSdkVersion())
        .setReflectively("buildVersion", packageConfigAndPlatformInfo.getPlatformBuildVersion());
  }

  /**
   * Value type for tracking a package config and information about the platform.
   */
  @Value.Immutable
  @BuckStyleTuple
  abstract static class AbstractApplePackageConfigAndPlatformInfo {
    public abstract ApplePackageConfig getConfig();

    /**
     * The apple cxx platform in question.
     *
     * As this value is architecture specific, it is omitted from equality computation, via
     * {@code Value.Auxiliary}. Since the actual apple "Platform" is architecture agnostic, proxy
     * values for the actual platform are used for equality comparison instead.
     */
    @Value.Auxiliary
    protected abstract AppleCxxPlatform getPlatform();

    /**
     * Path to the platform directory, where the external packager script might be located.
     *
     * This value should be omitted from rule key generation as it may vary across machines.
     */
    @Value.Derived
    public Path getPlatformPath() {
      return getPlatform().getAppleSdkPaths().getPlatformPath();
    }

    /**
     * The sdk version of the platform.
     *
     * This is used as a proxy for the version of the external packager.
     */
    @Value.Derived
    public String getSdkVersion() {
      return getPlatform().getAppleSdk().getVersion();
    }

    /**
     * The build version of the platform.
     *
     * This is used as a proxy for the version of the external packager.
     */
    @Value.Derived
    public Optional<String> getPlatformBuildVersion() {
      return getPlatform().getBuildVersion();
    }
  }

}
