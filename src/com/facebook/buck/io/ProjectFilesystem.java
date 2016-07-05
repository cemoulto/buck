/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.io;

import com.facebook.buck.config.Config;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.zip.CustomZipEntry;
import com.facebook.buck.zip.CustomZipOutputStream;
import com.facebook.buck.zip.ZipOutputStreams;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Strings;
import com.google.common.collect.Collections2;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.Channels;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import okio.BufferedSource;
import okio.Okio;

/**
 * An injectable service for interacting with the filesystem relative to the project root.
 */
public class ProjectFilesystem {

  /**
   * Controls the behavior of how the source should be treated when copying.
   */
  public enum CopySourceMode {
      /**
       * Copy the single source file into the destination path.
       */
      FILE,

      /**
       * Treat the source as a directory and copy each file inside it
       * to the destination path, which must be a directory.
       */
      DIRECTORY_CONTENTS_ONLY,

      /**
       * Treat the source as a directory. Copy the directory and its
       * contents to the destination path, which must be a directory.
       */
      DIRECTORY_AND_CONTENTS,
  }

  // Extended attribute bits for directories and symlinks; see:
  // http://unix.stackexchange.com/questions/14705/the-zip-formats-external-file-attribute
  @SuppressWarnings("PMD.AvoidUsingOctalValues")
  public static final long S_IFDIR = 0040000;
  @SuppressWarnings("PMD.AvoidUsingOctalValues")
  public static final long S_IFLNK = 0120000;

  // A non-exhaustive list of characters that might indicate that we're about to deal with a glob.
  private static final Pattern GLOB_CHARS = Pattern.compile("[\\*\\?\\{\\[]");

  @VisibleForTesting
  static final String BUCK_BUCKD_DIR_KEY = "buck.buckd_dir";

  private final Path projectRoot;

  private final Function<Path, Path> pathAbsolutifier;
  private final Function<Path, Path> pathRelativizer;

  private final Optional<ImmutableSet<Path>> whiteListedPaths;
  private final ImmutableSet<PathOrGlobMatcher> blackListedPaths;
  private final ImmutableSortedSet<Path> blackListedDirectories;

  // Defaults to false, and so paths should be valid.
  @VisibleForTesting
  protected boolean ignoreValidityOfPaths;

  public ProjectFilesystem(Path root) {
    this(
        root.getFileSystem(),
        root,
        Optional.<ImmutableSet<Path>>absent(),
        ImmutableSet.<PathOrGlobMatcher>of());
  }

  /**
   * There should only be one {@link ProjectFilesystem} created per process.
   */
  public ProjectFilesystem(
      Path projectRoot,
      Optional<ImmutableSet<Path>> whiteListedPaths,
      ImmutableSet<PathOrGlobMatcher> blackListedPaths) {
    this(
        projectRoot.getFileSystem(),
        projectRoot,
        whiteListedPaths,
        blackListedPaths);
  }

  public ProjectFilesystem(Path root, Config config) {
    this(
        root.getFileSystem(),
        root,
        Optional.<ImmutableSet<Path>>absent(),
        extractIgnorePaths(root, config));
  }

  private ProjectFilesystem(
      FileSystem vfs,
      final Path root,
      Optional<ImmutableSet<Path>> whiteListedPaths,
      ImmutableSet<PathOrGlobMatcher> blackListedPaths) {
    Preconditions.checkArgument(Files.isDirectory(root));
    Preconditions.checkState(vfs.equals(root.getFileSystem()));
    Preconditions.checkArgument(root.isAbsolute());
    this.projectRoot = MorePaths.normalize(root);
    this.pathAbsolutifier = new Function<Path, Path>() {
      @Override
      public Path apply(Path path) {
        return resolve(path);
      }
    };
    this.pathRelativizer = new Function<Path, Path>() {
      @Override
      public Path apply(Path input) {
        return projectRoot.relativize(input);
      }
    };
    this.whiteListedPaths = whiteListedPaths.transform(
        new Function<ImmutableSet<Path>, ImmutableSet<Path>>() {
          @Override
          public ImmutableSet<Path> apply(ImmutableSet<Path> input) {
            return MorePaths.filterForSubpaths(input, ProjectFilesystem.this.projectRoot);
          }
        });
    this.ignoreValidityOfPaths = false;
    this.blackListedPaths = blackListedPaths;

    this.blackListedDirectories = FluentIterable.from(this.blackListedPaths)
        .filter(new Predicate<PathOrGlobMatcher>() {
            @Override
            public boolean apply(PathOrGlobMatcher matcher) {
              return matcher.getType() == PathOrGlobMatcher.Type.PATH;
            }
        })
        .transform(new Function<PathOrGlobMatcher, Path>() {
          @Override
          public Path apply(PathOrGlobMatcher matcher) {
            Path path = matcher.getPath();
            ImmutableSet<Path> filtered = MorePaths.filterForSubpaths(
                ImmutableSet.<Path>of(path),
                root);
            if (filtered.isEmpty()) {
              return path;
            }
            return Iterables.getOnlyElement(filtered);
          }
        })
        // TODO(#10068334) So we claim to ignore this path to preserve existing behaviour, but we
        // really don't end up ignoring it in reality (see extractIgnorePaths).
        .append(ImmutableSet.of(root.getFileSystem().getPath(BuckConstant.BUCK_OUTPUT_DIRECTORY)))
        // "Path" is Iterable, so avoid adding each segment.
        // We use the default value here because that's what we've always done.
        .append(ImmutableSet.of(getCacheDir(root, Optional.of(BuckConstant.DEFAULT_CACHE_DIR))))
        .append(ImmutableSet.of(root.getFileSystem().getPath(BuckConstant.TRASH_DIR)))
        .toSortedSet(Ordering.<Path>natural());
  }

  private static Path getCacheDir(Path root, Optional<String> value) {
      String cacheDir = value.or(root.resolve(BuckConstant.DEFAULT_CACHE_DIR).toString());
      Path toReturn = root.getFileSystem().getPath(cacheDir);
      toReturn = MorePaths.expandHomeDir(toReturn);
      if (toReturn.isAbsolute()) {
         return toReturn;
       }
      ImmutableSet<Path> filtered = MorePaths.filterForSubpaths(ImmutableSet.of(toReturn), root);
      if (filtered.isEmpty()) {
         // OK. For some reason the relative path managed to be out of our directory.
             return toReturn;
       }
      return Iterables.getOnlyElement(filtered);
    }


  private static ImmutableSet<PathOrGlobMatcher> extractIgnorePaths(
      final Path root,
      Config config) {
    ImmutableSet.Builder<PathOrGlobMatcher> builder = ImmutableSet.builder();

    builder.add(new PathOrGlobMatcher(root, ".idea"));

    final String projectKey = "project";
    final String ignoreKey = "ignore";

    String buckdDirProperty = System.getProperty(BUCK_BUCKD_DIR_KEY, ".buckd");
    if (!Strings.isNullOrEmpty(buckdDirProperty)) {
      builder.add(new PathOrGlobMatcher(root, buckdDirProperty));
    }

    Path cacheDir = getCacheDir(root, config.getValue("cache", "dir"));
    builder.add(new PathOrGlobMatcher(cacheDir));

    builder.addAll(FluentIterable.from(config.getListWithoutComments(projectKey, ignoreKey))
        .transform(new Function<String, PathOrGlobMatcher>() {
          @Nullable
          @Override
          public PathOrGlobMatcher apply(String input) {
            // We don't really want to ignore the output directory when doing things like filesystem
            // walks, so return null
            if (BuckConstant.BUCK_OUTPUT_DIRECTORY.equals(input)) {
              return null; //root.getFileSystem().getPathMatcher("glob:**");
            }

            if (GLOB_CHARS.matcher(input).find()) {
              return new PathOrGlobMatcher(
                  root.getFileSystem().getPathMatcher("glob:" + input),
                  input);
            }
            return new PathOrGlobMatcher(root, input);
          }
        })
        // And now remove any null patterns
        .filter(Predicates.<PathOrGlobMatcher>notNull())
        .toList());

    return builder.build();
  }

  public Path getRootPath() {
    return projectRoot;
  }

  /**
   * @return the specified {@code path} resolved against {@link #getRootPath()} to an absolute path.
   */
  public Path resolve(Path path) {
    return MorePaths.normalize(resolvePathFromOtherVfs(path).toAbsolutePath());
  }

  public Path resolve(String path) {
    return MorePaths.normalize(getRootPath().resolve(path).toAbsolutePath());
  }

  /**
   * We often create {@link Path} instances using
   * {@link java.nio.file.Paths#get(String, String...)}, but there's no guarantee that the
   * underlying {@link FileSystem} is the default one.
   */
  protected Path resolvePathFromOtherVfs(Path path) {
    if (path.getFileSystem().equals(getRootPath().getFileSystem())) {
      return getRootPath().resolve(path);
    }
    return getRootPath().resolve(path.toString());
  }

  /**
   * @return A {@link Function} that applies {@link #resolve(Path)} to its parameter.
   */
  public Function<Path, Path> getAbsolutifier() {
    return pathAbsolutifier;
  }

  public Function<Path, Path> getRelativizer() {
    return pathRelativizer;
  }

  /**
   * @return A {@link ImmutableSet} of {@link Path} objects to have buck ignore.
   */
  public ImmutableSet<Path> getIgnorePaths() {
    return blackListedDirectories;
  }

  /**
   * // @deprecated Prefer operating on {@code Path}s directly, replaced by
   *    {@link #getPathForRelativePath(java.nio.file.Path)}.
   */
  public File getFileForRelativePath(String pathRelativeToProjectRoot) {
    return pathRelativeToProjectRoot.isEmpty()
        ? projectRoot.toFile()
        : getPathForRelativePath(pathRelativeToProjectRoot).toFile();
  }

  public Path getPathForRelativePath(Path pathRelativeToProjectRootOrJustAbsolute) {
    return resolvePathFromOtherVfs(pathRelativeToProjectRootOrJustAbsolute);
  }

  public Path getPathForRelativePath(String pathRelativeToProjectRoot) {
    return projectRoot.resolve(pathRelativeToProjectRoot);
  }


  /**
   * @param path Absolute path or path relative to the project root.
   * @return If {@code path} is relative, it is returned. If it is absolute and is inside the
   *         project root, it is relativized to the project root and returned. Otherwise an absent
   *         value is returned.
   */
  public Optional<Path> getPathRelativeToProjectRoot(Path path) {
    path = MorePaths.normalize(path);
    if (path.isAbsolute()) {
      if (path.startsWith(projectRoot)) {
        return Optional.of(MorePaths.relativize(projectRoot, path));
      } else {
        return Optional.absent();
      }
    } else {
      return Optional.of(path);
    }
  }

  /**
   * As {@link #getPathForRelativePath(java.nio.file.Path)}, but with the added twist that the
   * existence of the path is checked before returning.
   */
  public Path getPathForRelativeExistingPath(Path pathRelativeToProjectRoot) {
    Path file = getPathForRelativePath(pathRelativeToProjectRoot);

    if (ignoreValidityOfPaths) {
      return file;
    }

    // TODO(bolinfest): Eliminate this temporary exemption for symbolic links.
    if (Files.isSymbolicLink(file)) {
      return file;
    }

    if (!Files.exists(file)) {
      throw new RuntimeException(
          String.format("Not an ordinary file: '%s'.", pathRelativeToProjectRoot));
    }

    return file;
  }

  public boolean exists(Path pathRelativeToProjectRoot) {
    return Files.exists(getPathForRelativePath(pathRelativeToProjectRoot));
  }

  public long getFileSize(Path pathRelativeToProjectRoot) throws IOException {
    Path path = getPathForRelativePath(pathRelativeToProjectRoot);
    if (!Files.isRegularFile(path)) {
      throw new IOException("Cannot get size of " + path + " because it is not an ordinary file.");
    }
    return Files.size(path);
  }

  /**
   * Deletes a file specified by its path relative to the project root.
   *
   * Ignores the failure if the file does not exist.
   * @param pathRelativeToProjectRoot path to the file
   * @return {@code true} if the file was deleted, {@code false} if it did not exist
   */
  public boolean deleteFileAtPathIfExists(Path pathRelativeToProjectRoot) throws IOException {
    return Files.deleteIfExists(getPathForRelativePath(pathRelativeToProjectRoot));
  }

  /**
   * Deletes a file specified by its path relative to the project root.
   * @param pathRelativeToProjectRoot path to the file
   */
  public void deleteFileAtPath(Path pathRelativeToProjectRoot) throws IOException {
    Files.delete(getPathForRelativePath(pathRelativeToProjectRoot));
  }

  public Properties readPropertiesFile(Path pathToPropertiesFileRelativeToProjectRoot)
      throws IOException {
    Properties properties = new Properties();
    Path propertiesFile = getPathForRelativePath(pathToPropertiesFileRelativeToProjectRoot);
    if (Files.exists(propertiesFile)) {
      properties.load(Files.newBufferedReader(propertiesFile, Charsets.UTF_8));
      return properties;
    } else {
      throw new FileNotFoundException(propertiesFile.toString());
    }
  }

  /**
   * Checks whether there is a normal file at the specified path.
   */
  public boolean isFile(Path pathRelativeToProjectRoot) {
    return Files.isRegularFile(
        getPathForRelativePath(pathRelativeToProjectRoot));
  }

  public boolean isHidden(Path pathRelativeToProjectRoot) throws IOException {
    return Files.isHidden(getPathForRelativePath(pathRelativeToProjectRoot));
  }

  /**
   * Similar to {@link #walkFileTree(Path, FileVisitor)} except this takes in a path relative to
   * the project root.
   */
  public void walkRelativeFileTree(
      Path pathRelativeToProjectRoot,
      final FileVisitor<Path> fileVisitor) throws IOException {
    walkRelativeFileTree(pathRelativeToProjectRoot,
        EnumSet.of(FileVisitOption.FOLLOW_LINKS),
        fileVisitor);
  }

  /**
   * Walks a project-root relative file tree with a visitor and visit options.
   */
  public void walkRelativeFileTree(
      Path pathRelativeToProjectRoot,
      EnumSet<FileVisitOption> visitOptions,
      final FileVisitor<Path> fileVisitor) throws IOException {

    FileVisitor<Path> actualVisitor = wrapWithIgnoringFileVisitor(
        new FileVisitor<Path>() {
          @Override
          public FileVisitResult preVisitDirectory(
              Path dir, BasicFileAttributes attrs) throws IOException {
            return fileVisitor.preVisitDirectory(projectRoot.relativize(dir), attrs);
          }

          @Override
          public FileVisitResult visitFile(
              Path file, BasicFileAttributes attrs) throws IOException {
            return fileVisitor.visitFile(projectRoot.relativize(file), attrs);
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
            return fileVisitor.visitFileFailed(projectRoot.relativize(file), exc);
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            return fileVisitor.postVisitDirectory(projectRoot.relativize(dir), exc);
          }
        });

    Path rootPath = getPathForRelativePath(pathRelativeToProjectRoot);
    Files.walkFileTree(
        rootPath,
        visitOptions,
        Integer.MAX_VALUE,
        actualVisitor);
  }

  /**
   * Allows {@link Files#walkFileTree} to be faked in tests.
   */
  public void walkFileTree(Path root, FileVisitor<Path> fileVisitor) throws IOException {
    Files.walkFileTree(root, wrapWithIgnoringFileVisitor(fileVisitor));
  }

  public ImmutableSet<Path> getFilesUnderPath(Path pathRelativeToProjectRoot) throws IOException {
    return getFilesUnderPath(pathRelativeToProjectRoot, Predicates.<Path>alwaysTrue());
  }

  public ImmutableSet<Path> getFilesUnderPath(
      Path pathRelativeToProjectRoot,
      Predicate<Path> predicate) throws IOException {
    return getFilesUnderPath(
        pathRelativeToProjectRoot,
        predicate,
        EnumSet.of(FileVisitOption.FOLLOW_LINKS));
  }

  public ImmutableSet<Path> getFilesUnderPath(
      Path pathRelativeToProjectRoot,
      final Predicate<Path> predicate,
      EnumSet<FileVisitOption> visitOptions) throws IOException {
    final ImmutableSet.Builder<Path> paths = ImmutableSet.builder();
    walkRelativeFileTree(
        pathRelativeToProjectRoot,
        visitOptions,
        wrapWithIgnoringFileVisitor(
            new SimpleFileVisitor<Path>() {
              @Override
              public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) {
                if (predicate.apply(path)) {
                  paths.add(path);
                }
                return FileVisitResult.CONTINUE;
              }
            }));
    return paths.build();
  }

  /**
   * Allows {@link Files#isDirectory} to be faked in tests.
   */
  public boolean isDirectory(Path child, LinkOption... linkOptions) {
    return Files.isDirectory(resolve(child), linkOptions);
  }

  /**
   * Allows {@link Files#isExecutable} to be faked in tests.
   */
  public boolean isExecutable(Path child) {
    return Files.isExecutable(resolve(child));
  }

  /**
   * Allows {@link java.io.File#listFiles} to be faked in tests.
   *
   * // @deprecated Replaced by {@link #getDirectoryContents}
   */
  public File[] listFiles(Path pathRelativeToProjectRoot) throws IOException {
    Collection<Path> paths = getDirectoryContents(pathRelativeToProjectRoot);

    File[] result = new File[paths.size()];
    return Collections2.transform(paths, new Function<Path, File>() {
      @Override
      public File apply(Path input) {
        return input.toFile();
      }
    }).toArray(result);
  }

  public ImmutableCollection<Path> getDirectoryContents(Path pathToUse)
      throws IOException {
    Path path = pathToUse.isAbsolute() ? pathToUse : getPathForRelativePath(pathToUse);
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
      return FluentIterable.from(stream)
          .filter(new Predicate<Path>() {
            @Override
            public boolean apply(Path input) {
              return !isIgnored(pathRelativizer.apply(input));
            }
          })
          .transform(
              new Function<Path, Path>() {
                @Override
                public Path apply(Path absolutePath) {
                  return MorePaths.relativize(projectRoot, absolutePath);
                }
              })
          .toList();
    }
  }

  @VisibleForTesting
  protected PathListing.PathModifiedTimeFetcher getLastModifiedTimeFetcher() {
    return new PathListing.PathModifiedTimeFetcher() {
        @Override
        public FileTime getLastModifiedTime(Path path) throws IOException {
          return FileTime.fromMillis(ProjectFilesystem.this.getLastModifiedTime(path));
        }
    };
  }

  /**
   * Returns the files inside {@code pathRelativeToProjectRoot} which match {@code globPattern},
   * ordered in descending last modified time order. This will not obey the results of
   * {@link #isIgnored(Path)}.
   */
  public ImmutableSortedSet<Path> getSortedMatchingDirectoryContents(
      Path pathRelativeToProjectRoot,
      String globPattern)
    throws IOException {
    Path path = getPathForRelativePath(pathRelativeToProjectRoot);
    return PathListing.listMatchingPaths(
        path,
        globPattern,
        getLastModifiedTimeFetcher());
  }

  public long getLastModifiedTime(Path pathRelativeToProjectRoot) throws IOException {
    Path path = getPathForRelativePath(pathRelativeToProjectRoot);
    return Files.getLastModifiedTime(path).toMillis();
  }

  /**
   * Sets the last modified time for the given path.
   */
  public Path setLastModifiedTime(Path pathRelativeToProjectRoot, FileTime time)
      throws IOException {
    Path path = getPathForRelativePath(pathRelativeToProjectRoot);
    return Files.setLastModifiedTime(path, time);
  }

  /**
   * Recursively delete everything under the specified path.
   */
  public void deleteRecursively(Path pathRelativeToProjectRoot) throws IOException {
    MoreFiles.deleteRecursively(resolve(pathRelativeToProjectRoot));
  }

  /**
   * Recursively delete everything under the specified path. Ignore the failure if the file at the
   * specified path does not exist.
   */
  public void deleteRecursivelyIfExists(Path pathRelativeToProjectRoot) throws IOException {
    MoreFiles.deleteRecursivelyIfExists(resolve(pathRelativeToProjectRoot));
  }

  /**
   * Resolves the relative path against the project root and then calls
   * {@link Files#createDirectories(java.nio.file.Path,
   *            java.nio.file.attribute.FileAttribute[])}
   */
  public void mkdirs(Path pathRelativeToProjectRoot) throws IOException {
    Files.createDirectories(resolve(pathRelativeToProjectRoot));
  }

  /**
   * Creates a new file relative to the project root.
   */
  public Path createNewFile(Path pathRelativeToProjectRoot) throws IOException {
    Path path = getPathForRelativePath(pathRelativeToProjectRoot);
    return Files.createFile(path);
  }

  /**
   * // @deprecated Prefer operating on {@code Path}s directly, replaced by
   *  {@link #createParentDirs(java.nio.file.Path)}.
   */
  public void createParentDirs(String pathRelativeToProjectRoot) throws IOException {
    Path file = getPathForRelativePath(pathRelativeToProjectRoot);
    mkdirs(file.getParent());
  }

  /**
   * @param pathRelativeToProjectRoot Must identify a file, not a directory. (Unfortunately, we have
   *     no way to assert this because the path is not expected to exist yet.)
   */
  public void createParentDirs(Path pathRelativeToProjectRoot) throws IOException {
    Path file = resolve(pathRelativeToProjectRoot);
    Path directory = file.getParent();
    mkdirs(directory);
  }

  /**
   * Writes each line in {@code lines} with a trailing newline to a file at the specified path.
   * <p>
   * The parent path of {@code pathRelativeToProjectRoot} must exist.
   */
  public void writeLinesToPath(
      Iterable<String> lines,
      Path pathRelativeToProjectRoot,
      FileAttribute<?>... attrs)
      throws IOException {
    try (Writer writer =
         new BufferedWriter(
             new OutputStreamWriter(
                 newFileOutputStream(pathRelativeToProjectRoot, attrs),
                 Charsets.UTF_8))) {
      for (String line : lines) {
        writer.write(line);
        writer.write('\n');
      }
    }
  }

  public void writeContentsToPath(
      String contents,
      Path pathRelativeToProjectRoot,
      FileAttribute<?>... attrs)
      throws IOException {
    writeBytesToPath(contents.getBytes(Charsets.UTF_8), pathRelativeToProjectRoot, attrs);
  }

  public void writeBytesToPath(
      byte[] bytes,
      Path pathRelativeToProjectRoot,
      FileAttribute<?>... attrs) throws IOException {
    try (OutputStream outputStream = newFileOutputStream(pathRelativeToProjectRoot, attrs)) {
      outputStream.write(bytes);
    }
  }

  public OutputStream newFileOutputStream(
      Path pathRelativeToProjectRoot,
      FileAttribute<?>... attrs)
    throws IOException {
    return new BufferedOutputStream(
        Channels.newOutputStream(
            Files.newByteChannel(
                getPathForRelativePath(pathRelativeToProjectRoot),
                ImmutableSet.<OpenOption>of(
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE),
                attrs)));
  }

  public <A extends BasicFileAttributes> A readAttributes(
      Path pathRelativeToProjectRoot,
      Class<A> type,
      LinkOption... options)
    throws IOException {
    return Files.readAttributes(
        getPathForRelativePath(pathRelativeToProjectRoot), type, options);
  }

  public InputStream newFileInputStream(Path pathRelativeToProjectRoot)
    throws IOException {
    return new BufferedInputStream(
        Files.newInputStream(getPathForRelativePath(pathRelativeToProjectRoot)));
  }

  public BufferedSource   newSource(Path pathRelativeToProjectRoot) throws IOException {
    return Okio.buffer(Okio.source(getPathForRelativePath(pathRelativeToProjectRoot)));
  }

  /**
   * @param inputStream Source of the bytes. This method does not close this stream.
   */
  public void copyToPath(
      InputStream inputStream,
      Path pathRelativeToProjectRoot,
      CopyOption... options)
      throws IOException {
    Files.copy(inputStream, getPathForRelativePath(pathRelativeToProjectRoot),
        options);
  }

  /**
   * Copies a file to an output stream.
   */
  public void copyToOutputStream(
      Path pathRelativeToProjectRoot,
      OutputStream out)
      throws IOException {
    Files.copy(getPathForRelativePath(pathRelativeToProjectRoot), out);
  }

  public Optional<String> readFileIfItExists(Path pathRelativeToProjectRoot) {
    Path fileToRead = getPathForRelativePath(pathRelativeToProjectRoot);
    return readFileIfItExists(fileToRead, pathRelativeToProjectRoot.toString());
  }

  private Optional<String> readFileIfItExists(Path fileToRead, String pathRelativeToProjectRoot) {
    if (Files.isRegularFile(fileToRead)) {
      String contents;
      try {
        contents = new String(Files.readAllBytes(fileToRead), Charsets.UTF_8);
      } catch (IOException e) {
        // Alternatively, we could return Optional.absent(), though something seems suspicious if we
        // have already verified that fileToRead is a file and then we cannot read it.
        throw new RuntimeException("Error reading " + pathRelativeToProjectRoot, e);
      }
      return Optional.of(contents);
    } else {
      return Optional.absent();
    }
  }

  /**
   * Attempts to open the file for future read access. Returns {@link Optional#absent()} if the file
   * does not exist.
   */
  public Optional<Reader> getReaderIfFileExists(Path pathRelativeToProjectRoot) {
    Path fileToRead = getPathForRelativePath(pathRelativeToProjectRoot);
    if (Files.isRegularFile(fileToRead)) {
      try {
        return Optional.of(
            (Reader) new BufferedReader(
                new InputStreamReader(newFileInputStream(pathRelativeToProjectRoot))));
      } catch (Exception e) {
        throw new RuntimeException("Error reading " + pathRelativeToProjectRoot, e);
      }
    } else {
      return Optional.absent();
    }
  }

  /**
   * Attempts to read the first line of the file specified by the relative path. If the file does
   * not exist, is empty, or encounters an error while being read, {@link Optional#absent()} is
   * returned. Otherwise, an {@link Optional} with the first line of the file will be returned.
   *
   * // @deprecated PRefero operation on {@code Path}s directly, replaced by
   *  {@link #readFirstLine(java.nio.file.Path)}
   */
  public Optional<String> readFirstLine(String pathRelativeToProjectRoot) {
    return readFirstLine(projectRoot.getFileSystem().getPath(pathRelativeToProjectRoot));
  }

  /**
   * Attempts to read the first line of the file specified by the relative path. If the file does
   * not exist, is empty, or encounters an error while being read, {@link Optional#absent()} is
   * returned. Otherwise, an {@link Optional} with the first line of the file will be returned.
   */
  public Optional<String> readFirstLine(Path pathRelativeToProjectRoot) {
    Path file = getPathForRelativePath(pathRelativeToProjectRoot);
    return readFirstLineFromFile(file);
  }

  /**
   * Attempts to read the first line of the specified file. If the file does not exist, is empty,
   * or encounters an error while being read, {@link Optional#absent()} is returned. Otherwise, an
   * {@link Optional} with the first line of the file will be returned.
   */
  public Optional<String> readFirstLineFromFile(Path file) {
    try {
      return Optional.fromNullable(
          Files.newBufferedReader(file, Charsets.UTF_8).readLine());
    } catch (IOException e) {
      // Because the file is not even guaranteed to exist, swallow the IOException.
      return Optional.absent();
    }
  }

  public List<String> readLines(Path pathRelativeToProjectRoot) throws IOException {
    Path file = getPathForRelativePath(pathRelativeToProjectRoot);
    return Files.readAllLines(file, Charsets.UTF_8);
  }

  /**
   * // @deprecated Prefer operation on {@code Path}s directly, replaced by
   *  {@link Files#newInputStream(java.nio.file.Path, java.nio.file.OpenOption...)}.
   */
  public InputStream getInputStreamForRelativePath(Path path) throws IOException {
    Path file = getPathForRelativePath(path);
    return Files.newInputStream(file);
  }

  public String computeSha1(Path pathRelativeToProjectRootOrJustAbsolute) throws IOException {
    Path fileToHash = getPathForRelativePath(pathRelativeToProjectRootOrJustAbsolute);
    return Hashing.sha1().hashBytes(Files.readAllBytes(fileToHash)).toString();
  }

  public String computeSha256(Path pathRelativeToProjectRoot) throws IOException {
    Path fileToHash = getPathForRelativePath(pathRelativeToProjectRoot);
    return Hashing.sha256().hashBytes(Files.readAllBytes(fileToHash)).toString();
  }

  public void copy(Path source, Path target, CopySourceMode sourceMode) throws IOException {
    switch (sourceMode) {
      case FILE:
        Files.copy(
            resolve(source),
            resolve(target),
            StandardCopyOption.REPLACE_EXISTING);
        break;
      case DIRECTORY_CONTENTS_ONLY:
        MoreFiles.copyRecursively(resolve(source), resolve(target));
        break;
      case DIRECTORY_AND_CONTENTS:
        MoreFiles.copyRecursively(resolve(source), resolve(target.resolve(source.getFileName())));
        break;
    }
  }

  public void move(Path source, Path target, CopyOption... options) throws IOException {
    Files.move(resolve(source), resolve(target), options);

  }

  public void copyFolder(Path source, Path target) throws IOException {
    copy(source, target, CopySourceMode.DIRECTORY_CONTENTS_ONLY);
  }

  public void copyFile(Path source, Path target) throws IOException {
    copy(source, target, CopySourceMode.FILE);
  }

  public void createSymLink(Path symLink, Path realFile, boolean force)
      throws IOException {
    symLink = resolve(symLink);
    if (force) {
      Files.deleteIfExists(symLink);
    }
    if (Platform.detect() == Platform.WINDOWS) {
      if (isDirectory(realFile)) {
        // Creating symlinks to directories on Windows requires escalated privileges. We're just
        // going to have to copy things recursively.
        MoreFiles.copyRecursively(realFile, symLink);
      } else {
        // When sourcePath is relative, resolve it from the targetPath. We're creating a hard link
        // anyway.
        realFile = MorePaths.normalize(symLink.getParent().resolve(realFile));
        Files.createLink(symLink, realFile);
      }
    } else {
      Files.createSymbolicLink(symLink, realFile);
    }
  }

  /**
   * Returns the set of POSIX file permissions, or the empty set if the underlying file system
   * does not support POSIX file attributes.
   */
  public Set<PosixFilePermission> getPosixFilePermissions(Path path) throws IOException {
    Path resolvedPath = getPathForRelativePath(path);
    if (Files.getFileAttributeView(resolvedPath, PosixFileAttributeView.class) != null) {
      return Files.getPosixFilePermissions(resolvedPath);
    } else {
      return ImmutableSet.of();
    }
  }

  /**
   * Returns true if the file under {@code path} exists and is a symbolic
   * link, false otherwise.
   */
  public boolean isSymLink(Path path) throws IOException {
    return Files.isSymbolicLink(getPathForRelativePath(path));
  }

  /**
   * Returns the target of the specified symbolic link.
   */
  public Path readSymLink(Path path) throws IOException {
    return Files.readSymbolicLink(getPathForRelativePath(path));
  }

  /**
   * Takes a sequence of paths relative to the project root and writes a zip file to {@code out}
   * with the contents and structure that matches that of the specified paths.
   */
  public void createZip(Collection<Path> pathsToIncludeInZip, Path out) throws IOException {
    createZip(pathsToIncludeInZip, out, ImmutableMap.<Path, String>of());
  }

  /**
   * Similar to {@link #createZip(Collection, Path)}, but also takes a list of additional files to
   * write in the zip, including their contents, as a map. It's assumed only paths that should not
   * be ignored are passed to this method.
   */
  public void createZip(
      Collection<Path> pathsToIncludeInZip,
      Path out,
      ImmutableMap<Path, String> additionalFileContents) throws IOException {
    try (CustomZipOutputStream zip = ZipOutputStreams.newOutputStream(out)) {
      for (Path path : pathsToIncludeInZip) {
        boolean isDirectory = isDirectory(path);
        CustomZipEntry entry = new CustomZipEntry(path, isDirectory);

        // We want deterministic ZIPs, so avoid mtimes.
        entry.setFakeTime();

        entry.setExternalAttributes(getFileAttributesForZipEntry(path));

        zip.putNextEntry(entry);
        if (!isDirectory) {
          try (InputStream input = newFileInputStream(path)) {
            ByteStreams.copy(input, zip);
          }
        }
        zip.closeEntry();
      }

      for (Map.Entry<Path, String> fileContentsEntry : additionalFileContents.entrySet()) {
        CustomZipEntry entry = new CustomZipEntry(fileContentsEntry.getKey());
        // We want deterministic ZIPs, so avoid mtimes.
        entry.setFakeTime();
        zip.putNextEntry(entry);
        try (InputStream stream =
                 new ByteArrayInputStream(fileContentsEntry.getValue().getBytes(Charsets.UTF_8))) {
          ByteStreams.copy(stream, zip);
        }
        zip.closeEntry();
      }
    }
  }

  public long getFileAttributesForZipEntry(Path path) throws IOException {
    long mode = 0;
    // Support executable files.  If we detect this file is executable, store this
    // information as 0100 in the field typically used in zip implementations for
    // POSIX file permissions.  We'll use this information when unzipping.
    if (isExecutable(path)) {
      mode |=
          MorePosixFilePermissions.toMode(
              EnumSet.of(PosixFilePermission.OWNER_EXECUTE));
    }

    if (isDirectory(path)) {
      mode |= S_IFDIR;
    }

    if (isSymLink(path)) {
      mode |= S_IFLNK;
    }

    // Propagate any additional permissions
    mode |= MorePosixFilePermissions.toMode(getPosixFilePermissions(path));

    return mode << 16;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }

    if (!(other instanceof ProjectFilesystem)) {
      return false;
    }

    ProjectFilesystem that = (ProjectFilesystem) other;

    if (!Objects.equals(projectRoot, that.projectRoot)) {
      return false;
    }

    if (!Objects.equals(whiteListedPaths, that.whiteListedPaths)) {
      return false;
    }

    if (!Objects.equals(blackListedPaths, that.blackListedPaths)) {
      return false;
    }

    return true;
  }

  @Override
  public String toString() {
    return String.format(
        "%s (projectRoot=%s, whiteListedPaths=%s, blackListedPaths=%s",
        super.toString(),
        projectRoot,
        whiteListedPaths,
        blackListedPaths);
  }

  @Override
  public int hashCode() {
    return Objects.hash(projectRoot, whiteListedPaths, blackListedPaths);
  }

  private boolean isWhiteListed(Path path) {
    if (!whiteListedPaths.isPresent()) {
      return true;
    }
    for (Path whiteListedPath : whiteListedPaths.get()) {
      if (path.startsWith(whiteListedPath)) {
        return true;
      }
    }
    return false;
  }

  private boolean isBlackListed(Path path) {
    for (PathOrGlobMatcher blackListedPath : blackListedPaths) {
      if (blackListedPath.matches(path)) {
        return true;
      }
    }
    return false;
  }

  /**
   * @param path the path to check.
   * @return whether ignoredPaths contains path or any of its ancestors.
   */
  public boolean isIgnored(Path path) {
    Preconditions.checkArgument(!path.isAbsolute());
    return !isWhiteListed(path) || isBlackListed(path);
  }

  public Path createTempFile(
      Path directory,
      String prefix,
      String suffix,
      FileAttribute<?>... attrs)
      throws IOException {
    Path tmp = Files.createTempFile(resolve(directory), prefix, suffix, attrs);
    return getPathRelativeToProjectRoot(tmp).or(tmp);
  }

  public void touch(Path fileToTouch) throws IOException {
    if (exists(fileToTouch)) {
      setLastModifiedTime(fileToTouch, FileTime.fromMillis(System.currentTimeMillis()));
    } else {
      createNewFile(fileToTouch);
    }
  }

  /**
   * Wraps an existing {@link FileVisitor} so that paths that {@link #isIgnored(Path)} indicates
   * should be ignored are ignored.
   */
  private FileVisitor<Path> wrapWithIgnoringFileVisitor(final FileVisitor<Path> delegate) {
    return new FileVisitor<Path>() {
      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
          throws IOException {
        if (isIgnored(relativize(dir))) {
          return FileVisitResult.SKIP_SUBTREE;
        }
        return delegate.preVisitDirectory(dir, attrs);
      }

      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        if (isIgnored(relativize(file))) {
          return FileVisitResult.CONTINUE;
        }
        return delegate.visitFile(file, attrs);
      }

      @Override
      public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
        return delegate.visitFileFailed(file, exc);
      }

      @Override
      public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
        return delegate.postVisitDirectory(dir, exc);
      }

      private Path relativize(Path path) {
        if (path.startsWith(getRootPath())) {
          return pathRelativizer.apply(path);
        }
        return path;
      }
    };
  }

  public static class PathOrGlobMatcher {
    public enum Type {
        PATH,
        GLOB
    }
    private final Type type;
    private final Optional<Path> basePath;
    private final Optional<PathMatcher> globMatcher;
    private final Optional<String> globPattern;

    public PathOrGlobMatcher(Path basePath) {
      this.type = Type.PATH;
      this.basePath = Optional.of(basePath);
      this.globPattern = Optional.absent();
      this.globMatcher = Optional.absent();
    }

    public PathOrGlobMatcher(Path root, String basePath) {
      this(root.getFileSystem().getPath(basePath));
    }

    public PathOrGlobMatcher(PathMatcher globMatcher, String globPattern) {
      this.type = Type.GLOB;
      this.basePath = Optional.absent();
      this.globMatcher = Optional.of(globMatcher);
      this.globPattern = Optional.of(globPattern);
    }

    public Type getType() {
      return type;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }

      if (!(other instanceof PathOrGlobMatcher)) {
        return false;
      }

      PathOrGlobMatcher that = (PathOrGlobMatcher) other;

      return
          Objects.equals(type, that.type) &&
          Objects.equals(basePath, that.basePath) &&
          // We don't compare globMatcher here, since sun.nio.fs.UnixFileSystem.getPathMatcher()
          // returns an anonymous class which doesn't implement equals().
          Objects.equals(globPattern, that.globPattern);
    }

    @Override
    public int hashCode() {
      return Objects.hash(type, basePath, globPattern);
    }

    @Override
    public String toString() {
      return String.format(
          "%s type=%s basePath=%s globPattern=%s",
          super.toString(),
          type,
          basePath,
          globPattern);
    }

    public boolean matches(Path path) {
      switch (type) {
        case PATH:
          return path.startsWith(basePath.get());
        case GLOB:
          return globMatcher.get().matches(path);
      }
      throw new RuntimeException("Unsupported type " + type);
    }

    public Path getPath() {
      Preconditions.checkState(type == Type.PATH);
      return basePath.get();
    }
  }
}
