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

package com.facebook.buck.artifact_cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.io.BorrowablePath;
import com.facebook.buck.io.LazyPath;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.MoreExecutors;

import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;

public class TwoLevelArtifactCacheDecoratorTest {

  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  private static final RuleKey dummyRuleKey =
      new RuleKey("76b1c1beae69428db2d1befb31cf743ac8ce90df");
  private static final RuleKey dummyRuleKey2 =
      new RuleKey("1111111111111111111111111111111111111111");

  @Test
  public void testCacheFetch() throws InterruptedException, IOException {
    try (InMemoryArtifactCache inMemoryArtifactCache = new InMemoryArtifactCache();
         TwoLevelArtifactCacheDecorator twoLevelCache = new TwoLevelArtifactCacheDecorator(
             inMemoryArtifactCache,
             new ProjectFilesystem(tmp.getRoot()),
             BuckEventBusFactory.newInstance(),
             MoreExecutors.newDirectExecutorService(),
             /* performTwoLevelStores */ true,
             /* minimumTwoLevelStoredArtifactSize */ 0L,
             /* maximumTwoLevelStoredArtifactSize */ Optional.<Long>absent())) {
      LazyPath dummyFile = LazyPath.ofInstance(tmp.newFile());

      assertThat(
          twoLevelCache.fetch(dummyRuleKey, dummyFile).getType(),
          Matchers.equalTo(CacheResultType.MISS));

      twoLevelCache.store(
          ImmutableSet.of(dummyRuleKey),
          ImmutableMap.<String, String>of(),
          BorrowablePath.notBorrowablePath(dummyFile.get()));
      assertThat(
          twoLevelCache.fetch(dummyRuleKey, dummyFile).getType(),
          Matchers.equalTo(CacheResultType.HIT));

      twoLevelCache.store(
          ImmutableSet.of(dummyRuleKey2),
          ImmutableMap.<String, String>of(),
          BorrowablePath.notBorrowablePath(dummyFile.get()));

      assertThat(
          twoLevelCache.fetch(dummyRuleKey2, dummyFile).getType(),
          Matchers.equalTo(CacheResultType.HIT));
      assertThat(
          inMemoryArtifactCache.getArtifactCount(),
          Matchers.equalTo(3));
    }
  }

  private void testStoreThresholds(
      int artifactSize,
      int expectedArtifactsInCache) throws InterruptedException, IOException {
    try (InMemoryArtifactCache inMemoryArtifactCache = new InMemoryArtifactCache();
         TwoLevelArtifactCacheDecorator twoLevelCache = new TwoLevelArtifactCacheDecorator(
             inMemoryArtifactCache,
             new ProjectFilesystem(tmp.getRoot()),
             BuckEventBusFactory.newInstance(),
             MoreExecutors.newDirectExecutorService(),
             /* performTwoLevelStores */ true,
             /* minimumTwoLevelStoredArtifactSize */ 5L,
             /* maximumTwoLevelStoredArtifactSize */ Optional.of(10L))) {

      LazyPath lazyPath = LazyPath.ofInstance(tmp.newFile());
      Files.write(lazyPath.get(), new byte[artifactSize]);

      twoLevelCache.store(
          ImmutableSet.of(dummyRuleKey),
          ImmutableMap.<String, String>of(),
          BorrowablePath.notBorrowablePath(lazyPath.get()));
      assertThat(
          inMemoryArtifactCache.getArtifactCount(),
          Matchers.equalTo(expectedArtifactsInCache));
    }
  }

  @Test
  public void noTwoLevelStoreWhenFileSizeBelowThreshold() throws Exception {
    testStoreThresholds(
        /* artifactSize */ 3,
        /* expectedArtifactsInCache */ 1);
  }

  @Test
  public void twoLeveLStoreWhenFileSizeInRange() throws Exception {
    testStoreThresholds(
        /* artifactSize */ 6,
        /* expectedArtifactsInCache */ 2);
  }

  @Test
  public void noTwoLevelStoreWhenFileSizeAboveMax() throws Exception {
    testStoreThresholds(
        /* artifactSize */ 11,
        /* expectedArtifactsInCache */ 1);
  }

  @Test
  public void testMetadataIsNotShared() throws InterruptedException, IOException {
    try (InMemoryArtifactCache inMemoryArtifactCache = new InMemoryArtifactCache();
         TwoLevelArtifactCacheDecorator twoLevelCache = new TwoLevelArtifactCacheDecorator(
             inMemoryArtifactCache,
             new ProjectFilesystem(tmp.getRoot()),
             BuckEventBusFactory.newInstance(),
             MoreExecutors.newDirectExecutorService(),
             /* performTwoLevelStores */ true,
             /* minimumTwoLevelStoredArtifactSize */ 0L,
             /* maximumTwoLevelStoredArtifactSize */ Optional.<Long>absent())) {
      LazyPath dummyFile = LazyPath.ofInstance(tmp.newFile());

      final String testMetadataKey = "testMetaKey";
      twoLevelCache.store(
          ImmutableSet.of(dummyRuleKey),
          ImmutableMap.of(testMetadataKey, "value1"),
          BorrowablePath.notBorrowablePath(dummyFile.get()));
      twoLevelCache.store(
          ImmutableSet.of(dummyRuleKey2),
          ImmutableMap.of(testMetadataKey, "value2"),
          BorrowablePath.notBorrowablePath(dummyFile.get()));

      CacheResult fetch1 = twoLevelCache.fetch(dummyRuleKey, dummyFile);
      CacheResult fetch2 = twoLevelCache.fetch(dummyRuleKey2, dummyFile);
      // Content hashes should be the same
      assertEquals(
          fetch1.getMetadata().get(TwoLevelArtifactCacheDecorator.METADATA_KEY),
          fetch2.getMetadata().get(TwoLevelArtifactCacheDecorator.METADATA_KEY));
      // But the metadata shouldn't be shared
      assertNotEquals(
          fetch1.getMetadata().get(testMetadataKey),
          fetch2.getMetadata().get(testMetadataKey));

    }
  }

  @Test
  public void testCanRead2LStoresIfStoresDisabled() throws InterruptedException, IOException {
    try (InMemoryArtifactCache inMemoryArtifactCache = new InMemoryArtifactCache();
         TwoLevelArtifactCacheDecorator twoLevelCache = new TwoLevelArtifactCacheDecorator(
             inMemoryArtifactCache,
             new ProjectFilesystem(tmp.getRoot()),
             BuckEventBusFactory.newInstance(),
             MoreExecutors.newDirectExecutorService(),
             /* performTwoLevelStores */ true,
             /* minimumTwoLevelStoredArtifactSize */ 0L,
             /* maximumTwoLevelStoredArtifactSize */ Optional.<Long>absent());
      TwoLevelArtifactCacheDecorator twoLevelCacheNoStore = new TwoLevelArtifactCacheDecorator(
          inMemoryArtifactCache,
          new ProjectFilesystem(tmp.getRoot()),
          BuckEventBusFactory.newInstance(),
          MoreExecutors.newDirectExecutorService(),
             /* performTwoLevelStores */ false,
             /* minimumTwoLevelStoredArtifactSize */ 0L,
             /* maximumTwoLevelStoredArtifactSize */ Optional.<Long>absent())) {
      LazyPath dummyFile = LazyPath.ofInstance(tmp.newFile());

      twoLevelCache.store(
          ImmutableSet.of(dummyRuleKey),
          ImmutableMap.<String, String>of(),
          BorrowablePath.notBorrowablePath(dummyFile.get()));
      assertThat(
          inMemoryArtifactCache.getArtifactCount(),
          Matchers.equalTo(2));

      assertThat(
          twoLevelCacheNoStore.fetch(dummyRuleKey, dummyFile).getType(),
          Matchers.equalTo(CacheResultType.HIT));
    }
  }
}
