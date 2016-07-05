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

package com.facebook.buck.python;

import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Pair;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExternalTestRunnerRule;
import com.facebook.buck.rules.ExternalTestRunnerTestSpec;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestRule;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.TestRunningOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@SuppressWarnings("PMD.TestClassWithoutTestCases")
public class PythonTest
    extends AbstractBuildRule
    implements TestRule, HasRuntimeDeps, ExternalTestRunnerRule, BinaryBuildRule {

  @AddToRuleKey
  private final Supplier<ImmutableMap<String, String>> env;
  private final PythonBinary binary;
  private final ImmutableSortedSet<BuildRule> additionalDeps;
  private final ImmutableSet<Label> labels;
  private final Optional<Long> testRuleTimeoutMs;
  private final ImmutableSet<String> contacts;
  private final ImmutableSet<BuildRule> sourceUnderTest;
  private final ImmutableList<Pair<Float, ImmutableSet<Path>>> neededCoverage;

  public PythonTest(
      BuildRuleParams params,
      SourcePathResolver resolver,
      final Supplier<ImmutableMap<String, String>> env,
      final PythonBinary binary,
      ImmutableSortedSet<BuildRule> additionalDeps,
      ImmutableSet<BuildRule> sourceUnderTest,
      ImmutableSet<Label> labels,
      ImmutableList<Pair<Float, ImmutableSet<Path>>> neededCoverage,
      Optional<Long> testRuleTimeoutMs,
      ImmutableSet<String> contacts) {

    super(params, resolver);

    this.env = Suppliers.memoize(
        new Supplier<ImmutableMap<String, String>>() {
          @Override
          public ImmutableMap<String, String> get() {
            ImmutableMap.Builder<String, String> environment = ImmutableMap.builder();
            environment.putAll(binary.getExecutableCommand().getEnvironment(getResolver()));
            environment.putAll(env.get());
            return environment.build();
          }
        });
    this.binary = binary;
    this.additionalDeps = additionalDeps;
    this.sourceUnderTest = sourceUnderTest;
    this.labels = labels;
    this.neededCoverage = neededCoverage;
    this.testRuleTimeoutMs = testRuleTimeoutMs;
    this.contacts = contacts;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  @Override
  public Path getPathToOutput() {
    return binary.getPathToOutput();
  }

  @Override
  public ImmutableList<Step> runTests(
      BuildContext buildContext,
      ExecutionContext executionContext,
      TestRunningOptions options,
      TestRule.TestReportingCallback testReportingCallback) {
    return ImmutableList.of(
        new MakeCleanDirectoryStep(getProjectFilesystem(), getPathToTestOutputDirectory()),
        new PythonRunTestsStep(
            getProjectFilesystem().getRootPath(),
            getBuildTarget().getFullyQualifiedName(),
            binary.getExecutableCommand().getCommandPrefix(getResolver()),
            env,
            options.getTestSelectorList(),
            testRuleTimeoutMs,
            getProjectFilesystem().resolve(getPathToTestOutputResult())));
  }

  @Override
  public ImmutableSet<String> getContacts() {
    return contacts;
  }

  @Override
  public Path getPathToTestOutputDirectory() {
    return BuildTargets.getGenPath(
        getBuildTarget(),
        "__test_%s_output__");
  }

  public Path getPathToTestOutputResult() {
    return getPathToTestOutputDirectory().resolve("results.json");
  }

  @Override
  public boolean hasTestResultFiles(ExecutionContext executionContext) {
    return getProjectFilesystem().isFile(getPathToTestOutputResult());
  }

  @Override
  public ImmutableSet<BuildRule> getSourceUnderTest() {
    return sourceUnderTest;
  }

  @Override
  public ImmutableSet<Label> getLabels() {
    return labels;
  }

  @Override
  public Callable<TestResults> interpretTestResults(
      final ExecutionContext executionContext,
      boolean isUsingTestSelectors,
      final boolean isDryRun) {
    return new Callable<TestResults>() {
      @Override
      public TestResults call() throws Exception {
        ImmutableList.Builder<TestCaseSummary> summaries = ImmutableList.builder();
        if (!isDryRun) {
          Optional<String> resultsFileContents =
              getProjectFilesystem().readFileIfItExists(
                  getPathToTestOutputResult());
          ObjectMapper mapper = executionContext.getObjectMapper();
          TestResultSummary[] testResultSummaries = mapper.readValue(
              resultsFileContents.get(),
              TestResultSummary[].class);
          summaries.add(new TestCaseSummary(
              getBuildTarget().getFullyQualifiedName(),
              ImmutableList.copyOf(testResultSummaries)));
        }
        return TestResults.of(
            getBuildTarget(),
            summaries.build(),
            contacts,
            FluentIterable.from(labels).transform(Functions.toStringFunction()).toSet());
      }
    };
  }

  @Override
  public boolean runTestSeparately() {
    return false;
  }

  // A python test rule is actually just a {@link NoopBuildRule} which contains a references to
  // a {@link PythonBinary} rule, which is the actual test binary.  Therefore, we *need* this
  // rule around to run this test, so model this via the {@link HasRuntimeDeps} interface.
  @Override
  public ImmutableSortedSet<BuildRule> getRuntimeDeps() {
    return ImmutableSortedSet.<BuildRule>naturalOrder()
        .addAll(binary.getExecutableCommand().getDeps(getResolver()))
        .addAll(additionalDeps)
        .build();
  }

  @Override
  public boolean supportsStreamingTests() {
    return false;
  }

  @VisibleForTesting
  protected PythonBinary getBinary() {
    return binary;
  }

  @Override
  public Tool getExecutableCommand() {
    return binary.getExecutableCommand();
  }

  @Override
  public ExternalTestRunnerTestSpec getExternalTestRunnerSpec(
      ExecutionContext executionContext,
      TestRunningOptions testRunningOptions) {
    return ExternalTestRunnerTestSpec.builder()
        .setTarget(getBuildTarget())
        .setType("pyunit")
        .setNeededCoverage(neededCoverage)
        .addAllCommand(binary.getExecutableCommand().getCommandPrefix(getResolver()))
        .putAllEnv(env.get())
        .addAllLabels(getLabels())
        .addAllContacts(getContacts())
        .build();
  }

}
