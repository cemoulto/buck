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

package com.facebook.buck.rage;

import static com.facebook.buck.zip.ZipOutputStreams.HandleDuplicates.OVERWRITE_EXISTING;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.environment.BuildEnvironmentDescription;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.zip.CustomZipEntry;
import com.facebook.buck.zip.CustomZipOutputStream;
import com.facebook.buck.zip.ZipOutputStreams;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteStreams;
import com.google.common.io.CharStreams;

import org.immutables.value.Value;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Path;

/**
 * Takes care of actually writing out the report.
 */
public class DefectReporter {
  private static final String REPORT_FILE_NAME = "report.json";
  private static final int UPLOAD_TIMEOUT_MILLIS = 15 * 1000;

  private final ProjectFilesystem filesystem;
  private final ObjectMapper objectMapper;
  private final RageConfig rageConfig;

  public DefectReporter(
      ProjectFilesystem filesystem,
      ObjectMapper objectMapper,
      RageConfig rageConfig) {
    this.filesystem = filesystem;
    this.objectMapper = objectMapper;
    this.rageConfig = rageConfig;
  }

  private void addFilesToArchive(
      CustomZipOutputStream out,
      ImmutableSet<Path> paths) throws IOException {
    for (Path logFile : paths) {
      Preconditions.checkArgument(!logFile.isAbsolute(), "Should be a relative Path.", logFile);
      out.putNextEntry(new CustomZipEntry(logFile));
      try (InputStream input = filesystem.newFileInputStream(logFile)) {
        ByteStreams.copy(input, out);
      }
      out.closeEntry();
    }
  }

  public DefectSubmitResult submitReport(DefectReport defectReport) throws IOException {
    if (rageConfig.getReportUploadUri().isPresent()) {
      URI uri = rageConfig.getReportUploadUri().get();
      String response = uploadReport(defectReport, uri);
      return DefectSubmitResult.builder()
          .setReportSubmitLocation(uri.toString())
          .setReportSubmitMessage(response)
          .build();
    } else {
      filesystem.mkdirs(BuckConstant.BUCK_OUTPUT_PATH);
      Path defectReportPath = filesystem.createTempFile(
          BuckConstant.BUCK_OUTPUT_PATH,
          "defect_report",
          ".zip");
      try (OutputStream outputStream = filesystem.newFileOutputStream(defectReportPath)) {
        writeReport(defectReport, outputStream);
      }
      return DefectSubmitResult.builder()
          .setReportSubmitLocation(defectReportPath.toString())
          .setReportLocalLocation(defectReportPath)
          .build();
    }
  }

  private void writeReport(
      DefectReport defectReport,
      OutputStream outputStream) throws IOException {
    try (BufferedOutputStream baseOut = new BufferedOutputStream(outputStream);
         CustomZipOutputStream out =
             ZipOutputStreams.newOutputStream(baseOut, OVERWRITE_EXISTING)) {
      addFilesToArchive(out, defectReport.getIncludedPaths());
      out.putNextEntry(new CustomZipEntry(REPORT_FILE_NAME));
      objectMapper.writeValue(out, defectReport);
    }
  }

  private String uploadReport(DefectReport defectReport, URI uri) throws IOException {
    HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
    connection.setUseCaches(false);
    connection.setDoOutput(true);
    connection.setConnectTimeout(UPLOAD_TIMEOUT_MILLIS);
    connection.setReadTimeout(UPLOAD_TIMEOUT_MILLIS);
    connection.setRequestMethod("POST");
    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
    try {
      try (OutputStream outputStream = connection.getOutputStream()) {
        writeReport(defectReport, outputStream);
        outputStream.flush();
      }
      if (connection.getResponseCode() != 200) {
        throw new IOException(connection.getResponseMessage());
      }
      try (InputStream inputStream = connection.getInputStream()) {
        return CharStreams.toString(new InputStreamReader(inputStream, Charsets.UTF_8));
      }
    } catch (IOException e) {
      throw new HumanReadableException(
          "Failed uploading report to %s because %s",
          uri,
          e.getMessage());
    }
  }

  @Value.Immutable
  @BuckStyleImmutable
  interface AbstractDefectSubmitResult {
    String getReportSubmitLocation();
    Optional<Path> getReportLocalLocation();
    Optional<String> getReportSubmitMessage();
  }

  @Value.Immutable
  @BuckStyleImmutable
  interface AbstractDefectReport {
    Optional<UserReport> getUserReport();
    ImmutableSet<BuildId> getHighlightedBuildIds();
    ImmutableSet<Path> getIncludedPaths();
    BuildEnvironmentDescription getBuildEnvironmentDescription();
  }
}
