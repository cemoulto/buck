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

package com.facebook.buck.rules;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Pair;
import com.facebook.buck.rules.keys.DependencyFileRuleKeyBuilderFactory;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.NullFileHashCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.nio.file.Path;

import javax.annotation.Nullable;

public class FakeRuleKeyBuilderFactory
    implements RuleKeyBuilderFactory, DependencyFileRuleKeyBuilderFactory {

  private final ImmutableMap<BuildTarget, RuleKey> ruleKeys;
  private final FileHashCache fileHashCache;

  public FakeRuleKeyBuilderFactory(
      ImmutableMap<BuildTarget, RuleKey> ruleKeys,
      FileHashCache fileHashCache) {
    this.ruleKeys = ruleKeys;
    this.fileHashCache = fileHashCache;
  }

  public FakeRuleKeyBuilderFactory(ImmutableMap<BuildTarget, RuleKey> ruleKeys) {
    this(ruleKeys, new NullFileHashCache());
  }

  @Override
  public RuleKeyBuilder newInstance(final BuildRule buildRule) {
    SourcePathResolver resolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
     );
    return new RuleKeyBuilder(resolver, fileHashCache, this) {

      @Override
      public RuleKeyBuilder setReflectively(String key, @Nullable Object val) {
        return this;
      }

      @Override
      public RuleKey build() {
        return ruleKeys.get(buildRule.getBuildTarget());
      }

    };
  }

  @Override
  public RuleKey build(BuildRule buildRule) {
    return newInstance(buildRule).build();
  }

  @Override
  public RuleKey build(BuildRule rule, ImmutableList<Path> inputs) throws IOException {
    return build(rule);
  }

  @Override
  public Pair<RuleKey, ImmutableSet<SourcePath>> buildManifestKey(BuildRule rule) {
    return new Pair<>(build(rule), ImmutableSet.<SourcePath>of());
  }

}
