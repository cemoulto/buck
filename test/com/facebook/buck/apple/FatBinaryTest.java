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

package com.facebook.buck.apple;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.cxx.CxxCompilationDatabase;
import com.facebook.buck.cxx.CxxInferEnhancer;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import org.hamcrest.Matchers;
import org.junit.Test;

public class FatBinaryTest {
  @Test
  public void appleBinaryDescriptionShouldAllowMultiplePlatformFlavors() {
    assertTrue(
        FakeAppleRuleDescriptions.BINARY_DESCRIPTION.hasFlavors(
            ImmutableSet.<Flavor>of(
                ImmutableFlavor.of("iphoneos-i386"),
                ImmutableFlavor.of("iphoneos-x86_64"))));
  }

  @SuppressWarnings({"unchecked"})
  @Test
  public void appleBinaryDescriptionWithMultiplePlatformArgsShouldGenerateFatBinary()
      throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRule fatBinaryRule = AppleBinaryBuilder
        .createBuilder(
            BuildTargetFactory.newInstance("//foo:xctest#iphoneos-i386,iphoneos-x86_64"))
        .build(resolver, filesystem);

    assertThat(fatBinaryRule, instanceOf(FatBinary.class));

    ImmutableList<Step> steps = fatBinaryRule.getBuildSteps(
        FakeBuildContext.NOOP_CONTEXT,
        new FakeBuildableContext());

    assertThat(steps, hasSize(2));
    Step step = Iterables.getLast(steps);

    ExecutionContext executionContext = TestExecutionContext.newInstance();
    assertThat(step, instanceOf(ShellStep.class));
    ImmutableList<String> command =
        ((ShellStep) step).getShellCommand(executionContext);
    assertThat(
        command,
        Matchers.contains(
            endsWith("lipo"),
            equalTo("-create"),
            equalTo("-output"),
            containsString("foo/xctest#"),
            containsString("/xctest#"),
            containsString("/xctest#")));
  }

  @Test
  public void appleBinaryDescriptionWithMultipleDifferentSdksShouldFail() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    HumanReadableException exception = null;
    try {
      AppleBinaryBuilder
          .createBuilder(
              BuildTargetFactory.newInstance("//foo:xctest#iphoneos-i386,macosx-x86_64"))
          .build(resolver);
    } catch (HumanReadableException e) {
      exception = e;
    }
    assertThat(exception, notNullValue());
    assertThat(
        "Should throw exception about different architectures",
        exception.getHumanReadableErrorMessage(),
        endsWith("Fat binaries can only be generated from binaries compiled for the same SDK."));
  }

  @Test
  public void fatBinaryWithSpecialBuildActionShouldFail() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    HumanReadableException exception = null;
    Iterable<Flavor> forbiddenFlavors = ImmutableList.<Flavor>builder()
        .addAll(CxxInferEnhancer.InferFlavors.getAll())
        .add(CxxCompilationDatabase.COMPILATION_DATABASE)
        .build();

    for (Flavor flavor : forbiddenFlavors) {
      try {
        AppleBinaryBuilder
            .createBuilder(
                BuildTargetFactory.newInstance("//foo:xctest#" +
                        "iphoneos-i386,iphoneos-x86_64," + flavor.toString()))
            .build(resolver);
      } catch (HumanReadableException e) {
        exception = e;
      }
      assertThat(exception, notNullValue());
      assertThat(
          "Should throw exception about special build actions.",
          exception.getHumanReadableErrorMessage(),
          endsWith("Fat binaries is only supported when building an actual binary."));
    }
  }
}
