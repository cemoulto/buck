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

package com.facebook.buck.artifact_cache;

import static org.junit.Assert.assertThat;

import com.facebook.buck.cli.BuckConfigTestUtils;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.nio.file.Paths;

public class ArtifactCacheBuckConfigTest {

  @Test
  public void testWifiBlacklist() throws IOException {
    ArtifactCacheBuckConfig config = createFromText(
        "[cache]",
        "mode = http",
        "blacklisted_wifi_ssids = yolocoaster");
    ImmutableSet<HttpCacheEntry> httpCaches = config.getHttpCaches();
    assertThat(httpCaches, Matchers.hasSize(1));
    HttpCacheEntry cacheEntry = FluentIterable.from(httpCaches).get(0);

    assertThat(
        cacheEntry.isWifiUsableForDistributedCache(Optional.of("yolocoaster")),
        Matchers.is(false));
    assertThat(
        cacheEntry.isWifiUsableForDistributedCache(Optional.of("swagtastic")),
        Matchers.is(true));

    config = createFromText(
        "[cache]",
        "mode = http");
    httpCaches = config.getHttpCaches();
    assertThat(httpCaches, Matchers.hasSize(1));
    cacheEntry = FluentIterable.from(httpCaches).get(0);

    assertThat(
        cacheEntry.isWifiUsableForDistributedCache(Optional.of("yolocoaster")),
        Matchers.is(true));
  }

  @Test
  public void testMode() throws IOException {
    ArtifactCacheBuckConfig config = createFromText(
        "[cache]",
        "mode = http");
    assertThat(config.hasAtLeastOneWriteableCache(), Matchers.is(true));
    assertThat(
        config.getArtifactCacheModes(),
        Matchers.contains(ArtifactCacheBuckConfig.ArtifactCacheMode.http));

    config = createFromText(
        "[cache]",
        "mode = dir");
    assertThat(config.hasAtLeastOneWriteableCache(), Matchers.is(false));
    assertThat(
        config.getArtifactCacheModes(),
        Matchers.contains(ArtifactCacheBuckConfig.ArtifactCacheMode.dir));

    config = createFromText(
        "[cache]",
        "mode = dir, http");
    assertThat(config.hasAtLeastOneWriteableCache(), Matchers.is(true));
    assertThat(
        config.getArtifactCacheModes(),
        Matchers.containsInAnyOrder(
            ArtifactCacheBuckConfig.ArtifactCacheMode.dir,
            ArtifactCacheBuckConfig.ArtifactCacheMode.http));
  }

  @Test
  public void testHttpCacheSettings() throws Exception {
    ArtifactCacheBuckConfig config = createFromText(
        "[cache]",
        "http_max_concurrent_writes = 5",
        "http_writer_shutdown_timeout_seconds = 6",
        "http_timeout_seconds = 42",
        "http_url = http://test.host:1234",
        "http_read_headers = Foo: bar; Baz: meh",
        "http_write_headers = Authorization: none",
        "http_mode = readwrite");
    ImmutableSet<HttpCacheEntry> httpCaches = config.getHttpCaches();
    assertThat(httpCaches, Matchers.hasSize(1));
    HttpCacheEntry cacheEntry = FluentIterable.from(httpCaches).get(0);

    ImmutableMap.Builder<String, String> readBuilder = ImmutableMap.builder();
    ImmutableMap<String, String> expectedReadHeaders = readBuilder
        .put("Foo", "bar")
        .put("Baz", "meh")
        .build();
    ImmutableMap.Builder<String, String>  writeBuilder = ImmutableMap.builder();
    ImmutableMap<String, String> expectedWriteHeaders = writeBuilder
        .put("Authorization", "none")
        .build();

    assertThat(config.getHttpMaxConcurrentWrites(), Matchers.is(5));
    assertThat(config.getHttpWriterShutdownTimeout(), Matchers.is(6));
    assertThat(cacheEntry.getTimeoutSeconds(), Matchers.is(42));
    assertThat(cacheEntry.getUrl(), Matchers.equalTo(new URI("http://test.host:1234")));
    assertThat(cacheEntry.getReadHeaders(), Matchers.equalTo(expectedReadHeaders));
    assertThat(cacheEntry.getWriteHeaders(), Matchers.equalTo(expectedWriteHeaders));
    assertThat(
        cacheEntry.getCacheReadMode(),
        Matchers.is(ArtifactCacheBuckConfig.CacheReadMode.readwrite));
  }

  @Test
  public void testHttpCacheHeaderDefaultSettings() throws Exception {
    ArtifactCacheBuckConfig config = createFromText(
        "[cache]",
        "http_timeout_seconds = 42");
    ImmutableSet<HttpCacheEntry> httpCaches = config.getHttpCaches();
    assertThat(httpCaches, Matchers.hasSize(1));
    HttpCacheEntry cacheEntry = FluentIterable.from(httpCaches).get(0);

    // If the headers are not set we shouldn't get any by default.
    ImmutableMap.Builder<String, String>  readBuilder = ImmutableMap.builder();
    ImmutableMap<String, String> expectedReadHeaders = readBuilder.build();
    ImmutableMap.Builder<String, String>  writeBuilder = ImmutableMap.builder();
    ImmutableMap<String, String> expectedWriteHeaders = writeBuilder.build();

    assertThat(cacheEntry.getReadHeaders(), Matchers.equalTo(expectedReadHeaders));
    assertThat(cacheEntry.getWriteHeaders(), Matchers.equalTo(expectedWriteHeaders));
  }

  @Test
  public void testDirCacheSettings() throws IOException {
    ArtifactCacheBuckConfig config = createFromText(
        "[cache]",
        "dir = cache_dir",
        "dir_mode = readonly",
        "dir_max_size = 1022B");
    DirCacheEntry dirCacheConfig = config.getDirCache();

    assertThat(
        dirCacheConfig.getCacheDir(),
        Matchers.equalTo(Paths.get("cache_dir").toAbsolutePath()));
    assertThat(
        dirCacheConfig.getCacheReadMode(),
        Matchers.is(ArtifactCacheBuckConfig.CacheReadMode.readonly));
    assertThat(dirCacheConfig.getMaxSizeBytes(), Matchers.equalTo(Optional.of(1022L)));
  }

  @Test(expected = HumanReadableException.class)
  public void testMalformedHttpUrl() throws IOException {
    ArtifactCacheBuckConfig config = createFromText(
        "[cache]",
        "http_url = notaurl");

    config.getHttpCaches();
  }

  @Test(expected = HumanReadableException.class)
  public void testMalformedMode() throws IOException {
    ArtifactCacheBuckConfig config = createFromText(
        "[cache]",
        "dir_mode = notamode");

    config.getDirCache();
  }

  @Test
  public void testServedCacheAbsentByDefault() throws IOException {
    ArtifactCacheBuckConfig config = createFromText(
        "[cache]",
        "dir = ~/cache_dir");
    assertThat(config.getServedLocalCache(), Matchers.equalTo(Optional.<DirCacheEntry>absent()));
  }

  @Test
  public void testServedCacheInheritsDirAndSizeFromDirCache() throws IOException {
    ArtifactCacheBuckConfig config = createFromText(
        "[cache]",
        "serve_local_cache = true",
        "dir = /cache_dir");
    assertThat(
        config.getServedLocalCache(),
        Matchers.equalTo(Optional.of(
                DirCacheEntry.builder()
                    .setMaxSizeBytes(Optional.<Long>absent())
                    .setCacheDir(Paths.get("/cache_dir"))
                    .setCacheReadMode(ArtifactCacheBuckConfig.CacheReadMode.readonly)
                    .build())));

    config = createFromText(
        "[cache]",
        "serve_local_cache = true",
        "dir = /cache_dir",
        "dir_mode = readwrite",
        "dir_max_size = 42b");
    assertThat(
        config.getServedLocalCache(),
        Matchers.equalTo(Optional.of(
                DirCacheEntry.builder()
                    .setMaxSizeBytes(Optional.of(42L))
                    .setCacheDir(Paths.get("/cache_dir"))
                    .setCacheReadMode(ArtifactCacheBuckConfig.CacheReadMode.readonly)
                    .build())));
  }

  @Test
  public void testServedCacheMode() throws IOException {
    ArtifactCacheBuckConfig config = createFromText(
        "[cache]",
        "serve_local_cache = true",
        "dir = /cache_dir",
        "served_local_cache_mode = readwrite");
    assertThat(
        config.getServedLocalCache(),
        Matchers.equalTo(Optional.of(
                DirCacheEntry.builder()
                    .setMaxSizeBytes(Optional.<Long>absent())
                    .setCacheDir(Paths.get("/cache_dir"))
                    .setCacheReadMode(ArtifactCacheBuckConfig.CacheReadMode.readwrite)
                    .build())));
  }

  @Test
  public void testExpandUserHomeCacheDir() throws IOException {
    ArtifactCacheBuckConfig config = createFromText(
        "[cache]",
        "dir = ~/cache_dir");
    assertThat(
        "User home cache directory must be expanded.",
        config.getDirCache().getCacheDir(),
        Matchers.equalTo(MorePaths.expandHomeDir(Paths.get("~/cache_dir"))));
  }

  @Test
  public void testNamedHttpCachesOnly() throws IOException {
    ArtifactCacheBuckConfig config = createFromText(
        "[cache]",
        "http_cache_names = bob, fred",
        "",
        "[cache#bob]",
        "http_url = http://bob.com/",
        "",
        "[cache#fred]",
        "http_url = http://fred.com/",
        "http_timeout_seconds = 42",
        "http_mode = readonly",
        "blacklisted_wifi_ssids = yolo",
        "",
        "[cache#ignoreme]",
        "http_url = http://ignored.com/");

    assertThat(config.getHttpCaches(), Matchers.hasSize(2));

    HttpCacheEntry bobCache = FluentIterable.from(config.getHttpCaches()).get(0);
    assertThat(bobCache.getUrl(), Matchers.equalTo(URI.create("http://bob.com/")));
    assertThat(bobCache.getCacheReadMode(), Matchers.equalTo(
            ArtifactCacheBuckConfig.CacheReadMode.readwrite));
    assertThat(bobCache.getTimeoutSeconds(), Matchers.is(3));

    HttpCacheEntry fredCache = FluentIterable.from(config.getHttpCaches()).get(1);
    assertThat(fredCache.getUrl(), Matchers.equalTo(URI.create("http://fred.com/")));
    assertThat(fredCache.getTimeoutSeconds(), Matchers.is(42));
    assertThat(fredCache.getCacheReadMode(), Matchers.equalTo(
            ArtifactCacheBuckConfig.CacheReadMode.readonly));
    assertThat(fredCache.isWifiUsableForDistributedCache(Optional.of("wsad")), Matchers.is(true));
    assertThat(fredCache.isWifiUsableForDistributedCache(Optional.of("yolo")), Matchers.is(false));
  }

  @Test
  public void testNamedAndLegacyCaches() throws IOException {
    ArtifactCacheBuckConfig config = createFromText(
        "[cache]",
        "http_timeout_seconds = 42",
        "http_cache_names = bob",
        "",
        "[cache#bob]",
        "http_url = http://bob.com/");

    assertThat(config.getHttpCaches(), Matchers.hasSize(2));

    HttpCacheEntry legacyCache = FluentIterable.from(config.getHttpCaches()).get(0);
    assertThat(legacyCache.getUrl(), Matchers.equalTo(URI.create("http://localhost:8080/")));
    assertThat(legacyCache.getTimeoutSeconds(), Matchers.is(42));

    HttpCacheEntry bobCache = FluentIterable.from(config.getHttpCaches()).get(1);
    assertThat(bobCache.getUrl(), Matchers.equalTo(URI.create("http://bob.com/")));
    assertThat(bobCache.getCacheReadMode(), Matchers.equalTo(
            ArtifactCacheBuckConfig.CacheReadMode.readwrite));
    assertThat(bobCache.getTimeoutSeconds(), Matchers.is(3));
  }

  @Test
  public void errorMessageFormatter() throws IOException {
    final String testText = "this is a test";
    ArtifactCacheBuckConfig config = createFromText(
        "[cache]",
        "http_error_message_format = " + testText);

    HttpCacheEntry cache = FluentIterable.from(config.getHttpCaches()).get(0);
    assertThat(cache.getErrorMessageFormat(), Matchers.equalTo(testText));
  }

  public static ArtifactCacheBuckConfig createFromText(String... lines) throws IOException {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    StringReader reader = new StringReader(Joiner.on('\n').join(lines));
    return new ArtifactCacheBuckConfig(
        BuckConfigTestUtils.createFromReader(
            reader,
            projectFilesystem,
            Architecture.detect(),
            Platform.detect(),
            ImmutableMap.copyOf(System.getenv())));
  }

}
