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

package com.facebook.buck.apple;

import com.dd.plist.NSArray;
import com.dd.plist.NSNumber;
import com.dd.plist.NSObject;
import com.dd.plist.NSString;
import com.facebook.buck.cxx.BuildRuleWithBinary;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.HeaderVisibility;
import com.facebook.buck.cxx.NativeTestable;
import com.facebook.buck.file.WriteFile;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Either;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.FindAndReplaceStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.MoveStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.hash.HashCode;
import com.google.common.io.Files;
import com.google.common.util.concurrent.Futures;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Creates a bundle: a directory containing files and subdirectories, described by an Info.plist.
 */
public class AppleBundle
    extends AbstractBuildRule
    implements NativeTestable, BuildRuleWithAppleBundle, BuildRuleWithBinary {

  private static final Logger LOG = Logger.get(AppleBundle.class);
  private static final String CODE_SIGN_ENTITLEMENTS = "CODE_SIGN_ENTITLEMENTS";
  public static final String DSYM_DWARF_FILE_FOLDER = "Contents/Resources/DWARF/";

  @AddToRuleKey
  private final String extension;

  @AddToRuleKey
  private final Optional<String> productName;

  @AddToRuleKey
  private final SourcePath infoPlist;

  @AddToRuleKey
  private final ImmutableMap<String, String> infoPlistSubstitutions;

  @AddToRuleKey
  private final Optional<BuildRule> binary;

  @AddToRuleKey
  private final Optional<BuildRule> unstrippedBinaryRule;

  @AddToRuleKey
  private final Optional<AppleDsym> appleDsym;

  @AddToRuleKey
  private final AppleBundleDestinations destinations;

  @AddToRuleKey
  private final Set<SourcePath> resourceDirs;

  @AddToRuleKey
  private final Set<SourcePath> resourceFiles;

  @AddToRuleKey
  private final Set<SourcePath> dirsContainingResourceDirs;

  @AddToRuleKey
  private final Optional<ImmutableSet<SourcePath>> resourceVariantFiles;

  @AddToRuleKey
  private final Set<SourcePath> frameworks;

  @AddToRuleKey
  private final Tool ibtool;

  @AddToRuleKey
  private final ImmutableSortedSet<BuildTarget> tests;

  @AddToRuleKey
  private final String platformName;

  @AddToRuleKey
  private final String sdkName;

  @AddToRuleKey
  private final String sdkVersion;

  @AddToRuleKey
  private final ProvisioningProfileStore provisioningProfileStore;

  @AddToRuleKey
  private final CodeSignIdentityStore codeSignIdentityStore;

  @AddToRuleKey
  private final Optional<Tool> codesignAllocatePath;

  @AddToRuleKey
  private final Optional<Tool> swiftStdlibTool;

  // Need to use String here as RuleKeyBuilder requires that paths exist to compute hashes.
  @AddToRuleKey
  private final ImmutableMap<SourcePath, String> extensionBundlePaths;

  private final Optional<AppleAssetCatalog> assetCatalog;
  private final Optional<String> platformBuildVersion;
  private final Optional<String> xcodeVersion;
  private final Optional<String> xcodeBuildVersion;

  private final String minOSVersion;
  private final String binaryName;
  private final Path bundleRoot;
  private final Path binaryPath;
  private final Path bundleBinaryPath;

  private final boolean hasBinary;

  AppleBundle(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Either<AppleBundleExtension, String> extension,
      Optional<String> productName,
      SourcePath infoPlist,
      Map<String, String> infoPlistSubstitutions,
      Optional<BuildRule> binary,
      Optional<BuildRule> unstrippedBinaryRule,
      Optional<AppleDsym> appleDsym,
      AppleBundleDestinations destinations,
      Set<SourcePath> resourceDirs,
      Set<SourcePath> resourceFiles,
      Set<SourcePath> dirsContainingResourceDirs,
      ImmutableMap<SourcePath, String> extensionBundlePaths,
      Optional<ImmutableSet<SourcePath>> resourceVariantFiles,
      Set<SourcePath> frameworks,
      AppleCxxPlatform appleCxxPlatform,
      Optional<AppleAssetCatalog> assetCatalog,
      Set<BuildTarget> tests,
      CodeSignIdentityStore codeSignIdentityStore,
      ProvisioningProfileStore provisioningProfileStore) {
    super(params, resolver);
    this.extension = extension.isLeft() ?
        extension.getLeft().toFileExtension() :
        extension.getRight();
    this.productName = productName;
    this.infoPlist = infoPlist;
    this.infoPlistSubstitutions = ImmutableMap.copyOf(infoPlistSubstitutions);
    this.binary = binary;
    this.unstrippedBinaryRule = unstrippedBinaryRule;
    this.appleDsym = appleDsym;
    this.destinations = destinations;
    this.resourceDirs = resourceDirs;
    this.resourceFiles = resourceFiles;
    this.dirsContainingResourceDirs = dirsContainingResourceDirs;
    this.extensionBundlePaths = extensionBundlePaths;
    this.resourceVariantFiles = resourceVariantFiles;
    this.frameworks = frameworks;
    this.ibtool = appleCxxPlatform.getIbtool();
    this.assetCatalog = assetCatalog;
    this.binaryName = getBinaryName(getBuildTarget(), this.productName);
    this.bundleRoot = getBundleRoot(getBuildTarget(), this.binaryName, this.extension);
    this.binaryPath = this.destinations.getExecutablesPath()
        .resolve(this.binaryName);
    this.tests = ImmutableSortedSet.copyOf(tests);
    AppleSdk sdk = appleCxxPlatform.getAppleSdk();
    this.platformName = sdk.getApplePlatform().getName();
    this.sdkName = sdk.getName();
    this.sdkVersion = sdk.getVersion();
    this.minOSVersion = appleCxxPlatform.getMinVersion();
    this.platformBuildVersion = appleCxxPlatform.getBuildVersion();
    this.xcodeBuildVersion = appleCxxPlatform.getXcodeBuildVersion();
    this.xcodeVersion = appleCxxPlatform.getXcodeVersion();

    bundleBinaryPath = bundleRoot.resolve(binaryPath);
    hasBinary = binary.isPresent() && binary.get().getPathToOutput() != null;

    if (needCodeSign()) {
      this.provisioningProfileStore = provisioningProfileStore;
      this.codeSignIdentityStore = codeSignIdentityStore;
    } else {
      this.provisioningProfileStore = ProvisioningProfileStore.fromProvisioningProfiles(
          ImmutableList.<ProvisioningProfileMetadata>of());
      this.codeSignIdentityStore =
          CodeSignIdentityStore.fromIdentities(ImmutableList.<CodeSignIdentity>of());
    }
    this.codesignAllocatePath = appleCxxPlatform.getCodesignAllocate();
    this.swiftStdlibTool = appleCxxPlatform.getSwiftStdlibTool();
  }

  public static String getBinaryName(BuildTarget buildTarget, Optional<String> productName) {
    if (productName.isPresent()) {
      return productName.get();
    } else {
      return buildTarget.getShortName();
    }
  }

  public static Path getBundleRoot(BuildTarget buildTarget, String binaryName, String extension) {
    return BuildTargets
        .getGenPath(buildTarget, "%s")
        .resolve(binaryName + "." + extension);
  }

  public String getExtension() {
    return extension;
  }

  @Override
  public Path getPathToOutput() {
    return bundleRoot;
  }

  public Path getInfoPlistPath() {
    return getMetadataPath().resolve("Info.plist");
  }

  public Path getUnzippedOutputFilePathToBinary() {
    return this.binaryPath;
  }

  private Path getMetadataPath() {
    return bundleRoot.resolve(destinations.getMetadataPath());
  }

  public String getPlatformName() {
    return platformName;
  }

  public Optional<BuildRule> getBinary() {
    return binary;
  }

  public boolean isLegacyWatchApp() {
    return extension.equals(AppleBundleExtension.APP.toFileExtension()) &&
        binary.isPresent() &&
        binary.get().getBuildTarget().getFlavors()
            .contains(AppleBinaryDescription.LEGACY_WATCH_FLAVOR);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    ImmutableList.Builder<Step> stepsBuilder = ImmutableList.builder();

    Path metadataPath = getMetadataPath();

    Path infoPlistInputPath = getResolver().getAbsolutePath(infoPlist);
    Path infoPlistSubstitutionTempPath =
        BuildTargets.getScratchPath(getBuildTarget(), "%s.plist");
    Path infoPlistOutputPath = metadataPath.resolve("Info.plist");

    stepsBuilder.add(
        new MakeCleanDirectoryStep(getProjectFilesystem(), bundleRoot),
        new MkdirStep(getProjectFilesystem(), metadataPath),
        // TODO(bhamiltoncx): This is only appropriate for .app bundles.
        new WriteFileStep(
            getProjectFilesystem(),
            "APPLWRUN",
            metadataPath.resolve("PkgInfo"),
            /* executable */ false),
        new MkdirStep(getProjectFilesystem(), infoPlistSubstitutionTempPath.getParent()),
        new FindAndReplaceStep(
            getProjectFilesystem(),
            infoPlistInputPath,
            infoPlistSubstitutionTempPath,
            InfoPlistSubstitution.createVariableExpansionFunction(
                withDefaults(
                    infoPlistSubstitutions,
                    ImmutableMap.of(
                        "EXECUTABLE_NAME", binaryName,
                        "PRODUCT_NAME", binaryName
                    ))
            )),
        new PlistProcessStep(
            getProjectFilesystem(),
            infoPlistSubstitutionTempPath,
            infoPlistOutputPath,
            getInfoPlistAdditionalKeys(),
            getInfoPlistOverrideKeys(),
            PlistProcessStep.OutputFormat.BINARY));

    if (hasBinary) {
      appendCopyBinarySteps(stepsBuilder);
      appendCopyDsymStep(stepsBuilder, buildableContext);
    }

    Path resourcesDestinationPath = bundleRoot.resolve(this.destinations.getResourcesPath());
    if (
        !Iterables.isEmpty(
            Iterables.concat(resourceDirs, dirsContainingResourceDirs, resourceFiles))) {
      stepsBuilder.add(new MkdirStep(getProjectFilesystem(), resourcesDestinationPath));
      for (SourcePath dir : resourceDirs) {
        stepsBuilder.add(
            CopyStep.forDirectory(
                getProjectFilesystem(),
                getResolver().getAbsolutePath(dir),
                resourcesDestinationPath,
                CopyStep.DirectoryMode.DIRECTORY_AND_CONTENTS));
      }
      for (SourcePath dir : dirsContainingResourceDirs) {
        stepsBuilder.add(
            CopyStep.forDirectory(
                getProjectFilesystem(),
                getResolver().getAbsolutePath(dir),
                resourcesDestinationPath,
                CopyStep.DirectoryMode.CONTENTS_ONLY));
      }
      for (SourcePath file : resourceFiles) {
        // TODO(shs96c): Check that this work cross-cell
        Path resolvedFilePath = getResolver().getRelativePath(file);
        Path destinationPath = resourcesDestinationPath.resolve(resolvedFilePath.getFileName());
        addResourceProcessingSteps(resolvedFilePath, destinationPath, stepsBuilder);
      }
    }

    addStepsToCopyExtensionBundlesDependencies(stepsBuilder);

    if (resourceVariantFiles.isPresent()) {
      for (SourcePath variantSourcePath : resourceVariantFiles.get()) {
        // TODO(shs96c): Ensure this works cross-cell, as relative path begins with "buck-out"
        Path variantFilePath = getResolver().getRelativePath(variantSourcePath);

        Path variantDirectory = variantFilePath.getParent();
        if (variantDirectory == null || !variantDirectory.toString().endsWith(".lproj")) {
          throw new HumanReadableException(
              "Variant files have to be in a directory with name ending in '.lproj', " +
                  "but '%s' is not.",
              variantFilePath);
        }

        Path bundleVariantDestinationPath =
            resourcesDestinationPath.resolve(variantDirectory.getFileName());
        stepsBuilder.add(new MkdirStep(getProjectFilesystem(), bundleVariantDestinationPath));

        Path destinationPath = bundleVariantDestinationPath.resolve(variantFilePath.getFileName());
        addResourceProcessingSteps(variantFilePath, destinationPath, stepsBuilder);
      }
    }

    if (!frameworks.isEmpty()) {
      Path frameworksDestinationPath = bundleRoot.resolve(this.destinations.getFrameworksPath());
      stepsBuilder.add(new MkdirStep(getProjectFilesystem(), frameworksDestinationPath));
      for (SourcePath framework : frameworks) {
        stepsBuilder.add(
            CopyStep.forDirectory(
                getProjectFilesystem(),
                getResolver().getAbsolutePath(framework),
                frameworksDestinationPath,
                CopyStep.DirectoryMode.DIRECTORY_AND_CONTENTS));
      }
    }

    if (assetCatalog.isPresent()) {
      Path bundleDir = assetCatalog.get().getOutputDir();
      stepsBuilder.add(
          CopyStep.forDirectory(
              getProjectFilesystem(),
              bundleDir,
              bundleRoot,
              CopyStep.DirectoryMode.CONTENTS_ONLY));
    }

    if (needCodeSign()) {
      // Copy the .mobileprovision file if the platform requires it, and sign the executable.
      Optional<Path> entitlementsPlist = Optional.absent();
      final Path srcRoot = getProjectFilesystem().getRootPath().resolve(
          getBuildTarget().getBasePath());
      Optional<String> entitlementsPlistString =
          InfoPlistSubstitution.getVariableExpansionForPlatform(
              CODE_SIGN_ENTITLEMENTS,
              platformName,
              withDefaults(
                  infoPlistSubstitutions,
                  ImmutableMap.of(
                      "SOURCE_ROOT", srcRoot.toString(),
                      "SRCROOT", srcRoot.toString()
                  )));
      if (entitlementsPlistString.isPresent()) {
        entitlementsPlist = Optional.of(srcRoot.resolve(Paths.get(entitlementsPlistString.get())));
      }

      final Path signingEntitlementsTempPath =
          BuildTargets.getScratchPath(getBuildTarget(), "%s.xcent");

      final ProvisioningProfileCopyStep provisioningProfileCopyStep =
          new ProvisioningProfileCopyStep(
              getProjectFilesystem(),
              infoPlistOutputPath,
              Optional.<String>absent(),  // Provisioning profile UUID -- find automatically.
              entitlementsPlist,
              provisioningProfileStore,
              resourcesDestinationPath.resolve("embedded.mobileprovision"),
              signingEntitlementsTempPath,
              codeSignIdentityStore);
      stepsBuilder.add(provisioningProfileCopyStep);

      Supplier<CodeSignIdentity> codeSignIdentitySupplier = new Supplier<CodeSignIdentity>() {
        @Override
        public CodeSignIdentity get() {
          // Using getUnchecked here because the previous step should already throw if exception
          // occurred, and this supplier would never be evaluated.
          ProvisioningProfileMetadata selectedProfile = Futures.getUnchecked(
              provisioningProfileCopyStep.getSelectedProvisioningProfileFuture());
          ImmutableSet<HashCode> fingerprints =
              selectedProfile.getDeveloperCertificateFingerprints();
          if (fingerprints.isEmpty()) {
            // No constraints, pick an arbitrary identity.
            // If no identities are available, use an ad-hoc identity.
            return
                Iterables.getFirst(codeSignIdentityStore.getIdentities(), CodeSignIdentity.AD_HOC);
          }
          for (CodeSignIdentity identity : codeSignIdentityStore.getIdentities()) {
            if (identity.getFingerprint().isPresent() &&
                fingerprints.contains(identity.getFingerprint().get())) {
              return identity;
            }
          }
          throw new HumanReadableException(
              "No code sign identity available for provisioning profile: %s\n" +
                  "Profile requires an identity with one of the following SHA1 fingerprints " +
                  "available in your keychain: \n  %s",
              selectedProfile.getProfilePath(),
              Joiner.on("\n  ").join(fingerprints));
        }
      };

      addSwiftStdlibStepIfNeeded(Optional.of(codeSignIdentitySupplier), stepsBuilder);

      stepsBuilder.add(
          new CodeSignStep(
              getProjectFilesystem().getRootPath(),
              getResolver(),
              resourcesDestinationPath,
              signingEntitlementsTempPath,
              codeSignIdentitySupplier,
              codesignAllocatePath));
    } else {
      addSwiftStdlibStepIfNeeded(Optional.<Supplier<CodeSignIdentity>>absent(), stepsBuilder);
    }

    // Ensure the bundle directory is archived so we can fetch it later.
    buildableContext.recordArtifact(getPathToOutput());

    return stepsBuilder.build();
  }

  private void appendCopyBinarySteps(ImmutableList.Builder<Step> stepsBuilder) {
    Preconditions.checkArgument(hasBinary);

    final Path binaryOutputPath = binary.get().getPathToOutput();
    Preconditions.checkNotNull(binaryOutputPath);

    copyBinaryIntoBundle(stepsBuilder, binaryOutputPath);
    copyAnotherCopyOfWatchKitStub(stepsBuilder, binaryOutputPath);
  }

  private void copyBinaryIntoBundle(
      ImmutableList.Builder<Step> stepsBuilder,
      Path binaryOutputPath) {
    stepsBuilder.add(
        new MkdirStep(
            getProjectFilesystem(),
            bundleRoot.resolve(this.destinations.getExecutablesPath())));
    stepsBuilder.add(
        CopyStep.forFile(
            getProjectFilesystem(),
            binaryOutputPath,
            bundleBinaryPath));
  }

  private void copyAnotherCopyOfWatchKitStub(
      ImmutableList.Builder<Step> stepsBuilder,
      Path binaryOutputPath) {
    if (binary.get() instanceof WriteFile) {
      final Path watchKitStubDir = bundleRoot.resolve("_WatchKitStub");
      stepsBuilder.add(
          new MkdirStep(getProjectFilesystem(), watchKitStubDir),
          CopyStep.forFile(
              getProjectFilesystem(),
              binaryOutputPath,
              watchKitStubDir.resolve("WK")));
    }
  }

  private void appendCopyDsymStep(
      ImmutableList.Builder<Step> stepsBuilder,
      BuildableContext buildableContext) {
    if (appleDsym.isPresent()) {
      stepsBuilder.add(
          CopyStep.forDirectory(
              getProjectFilesystem(),
              appleDsym.get().getPathToOutput(),
              bundleRoot.getParent(),
              CopyStep.DirectoryMode.DIRECTORY_AND_CONTENTS));
      appendDsymRenameStepToMatchBundleName(stepsBuilder, buildableContext);
    }
  }

  private void appendDsymRenameStepToMatchBundleName(
      ImmutableList.Builder<Step> stepsBuilder,
      BuildableContext buildableContext) {
    Preconditions.checkArgument(hasBinary && appleDsym.isPresent());

    // rename dSYM bundle to match bundle name
    Path dsymPath = appleDsym.get().getPathToOutput();
    Path dsymSourcePath = bundleRoot.getParent().resolve(dsymPath.getFileName());
    Path dsymDestinationPath = bundleRoot.getParent().resolve(
        bundleRoot.getFileName() + "." + AppleBundleExtension.DSYM.toFileExtension());
    stepsBuilder.add(new RmStep(getProjectFilesystem(), dsymDestinationPath, true, true));
    stepsBuilder.add(new MoveStep(getProjectFilesystem(), dsymSourcePath, dsymDestinationPath));

    String dwarfFilename =
        AppleDsym.getDwarfFilenameForDsymTarget(appleDsym.get().getBuildTarget());
    if (unstrippedBinaryRule.isPresent()) {
      Path unstrippedOutput = unstrippedBinaryRule.get().getPathToOutput();
      Preconditions.checkNotNull(
          unstrippedOutput,
          "Unstripped binary %s of bundle %s has null output path. It shouldn't be null, as " +
              "it used to determine the name of dwarf file inside dSYM bundle.",
          unstrippedBinaryRule.get(), this);
      dwarfFilename = unstrippedOutput.getFileName().toString();
    }

    // rename DWARF file inside dSYM bundle to match bundle name
    Path dwarfFolder = dsymDestinationPath.resolve(DSYM_DWARF_FILE_FOLDER);
    Path dwarfSourcePath = dwarfFolder.resolve(dwarfFilename);
    Path dwarfDestinationPath = dwarfFolder.resolve(MorePaths.getNameWithoutExtension(bundleRoot));
    stepsBuilder.add(new MoveStep(getProjectFilesystem(), dwarfSourcePath, dwarfDestinationPath));

    // record dSYM so we can fetch it from cache
    buildableContext.recordArtifact(dsymDestinationPath);
  }

  public void addStepsToCopyExtensionBundlesDependencies(
      ImmutableList.Builder<Step> stepsBuilder) {
    for (Map.Entry<SourcePath, String> entry : extensionBundlePaths.entrySet()) {
      Path destPath = bundleRoot.resolve(entry.getValue());
      stepsBuilder.add(new MkdirStep(getProjectFilesystem(), destPath));
      stepsBuilder.add(
        CopyStep.forDirectory(
            getProjectFilesystem(),
            getResolver().getAbsolutePath(entry.getKey()),
            destPath,
            CopyStep.DirectoryMode.DIRECTORY_AND_CONTENTS));
    }
  }

  static ImmutableMap<String, String> withDefaults(
      ImmutableMap<String, String> map,
      ImmutableMap<String, String> defaults) {
    ImmutableMap.Builder<String, String> builder = ImmutableMap.<String, String>builder()
        .putAll(map);
    for (ImmutableMap.Entry<String, String> entry : defaults.entrySet()) {
      if (!map.containsKey(entry.getKey())) {
        builder = builder.put(entry.getKey(), entry.getValue());
      }
    }
    return builder.build();
  }

  private ImmutableMap<String, NSObject> getInfoPlistOverrideKeys() {
    ImmutableMap.Builder<String, NSObject> keys = ImmutableMap.builder();

    if (platformName.contains("osx")) {
      keys.put("LSRequiresIPhoneOS", new NSNumber(false));
    } else if (!platformName.contains("watch") && !isLegacyWatchApp()) {
      keys.put("LSRequiresIPhoneOS", new NSNumber(true));
    }

    return keys.build();
  }

  private ImmutableMap<String, NSObject> getInfoPlistAdditionalKeys() {
    ImmutableMap.Builder<String, NSObject> keys = ImmutableMap.builder();

    if (platformName.contains("osx")) {
      keys.put("NSHighResolutionCapable", new NSNumber(true));
      keys.put("NSSupportsAutomaticGraphicsSwitching", new NSNumber(true));
      keys.put("CFBundleSupportedPlatforms", new NSArray(new NSString("MacOSX")));
    } else if (platformName.contains("iphoneos")) {
      keys.put("CFBundleSupportedPlatforms", new NSArray(new NSString("iPhoneOS")));
    } else if (platformName.contains("iphonesimulator")) {
      keys.put("CFBundleSupportedPlatforms", new NSArray(new NSString("iPhoneSimulator")));
    }

    keys.put("DTPlatformName", new NSString(platformName));
    keys.put("DTPlatformVersion", new NSString(sdkVersion));
    keys.put("DTSDKName", new NSString(sdkName + sdkVersion));
    keys.put("MinimumOSVersion", new NSString(minOSVersion));
    if (platformBuildVersion.isPresent()) {
      keys.put("DTPlatformBuild", new NSString(platformBuildVersion.get()));
      keys.put("DTSDKBuild", new NSString(platformBuildVersion.get()));
    }

    if (xcodeBuildVersion.isPresent()) {
      keys.put("DTXcodeBuild", new NSString(xcodeBuildVersion.get()));
    }

    if (xcodeVersion.isPresent()) {
      keys.put("DTXcode", new NSString(xcodeVersion.get()));
    }

    return keys.build();
  }

  private void addSwiftStdlibStepIfNeeded(
      Optional<Supplier<CodeSignIdentity>> codeSignIdentitySupplier,
      ImmutableList.Builder<Step> stepsBuilder) {
    // It's apparently safe to run this even on a non-swift bundle (in that case, no libs
    // are copied over).
    if (swiftStdlibTool.isPresent()) {
      ImmutableList.Builder<String> swiftStdlibCommand = ImmutableList.builder();
      swiftStdlibCommand.addAll(swiftStdlibTool.get().getCommandPrefix(getResolver()));
      swiftStdlibCommand.add(
          "--scan-executable",
          bundleBinaryPath.toString(),
          "--scan-folder",
          bundleRoot.resolve(destinations.getFrameworksPath()).toString(),
          "--scan-folder",
          bundleRoot.resolve(destinations.getPlugInsPath()).toString());

      stepsBuilder.add(
          new SwiftStdlibStep(
              getProjectFilesystem().getRootPath(),
              BuildTargets.getScratchPath(getBuildTarget(), "__swift_temp__%s"),
              bundleRoot.resolve(Paths.get("Frameworks")),
              swiftStdlibCommand.build(),
              codeSignIdentitySupplier)
      );
    }
  }

  private void addStoryboardProcessingSteps(
      Path sourcePath,
      Path destinationPath,
      ImmutableList.Builder<Step> stepsBuilder) {
    if (platformName.contains("watch")) {
      LOG.debug("Compiling storyboard %s to storyboardc %s and linking",
          sourcePath,
          destinationPath);

      Path compiledStoryboardPath =
          BuildTargets.getScratchPath(getBuildTarget(), "%s.storyboardc");
      stepsBuilder.add(
          new IbtoolStep(
              getProjectFilesystem(),
              ibtool.getEnvironment(getResolver()),
              ibtool.getCommandPrefix(getResolver()),
              ImmutableList.of("--target-device", "watch", "--compile"),
              sourcePath,
              compiledStoryboardPath));

      stepsBuilder.add(
          new IbtoolStep(
              getProjectFilesystem(),
              ibtool.getEnvironment(getResolver()),
              ibtool.getCommandPrefix(getResolver()),
              ImmutableList.of("--target-device", "watch", "--link"),
              compiledStoryboardPath,
              destinationPath.getParent()));

    } else {
      LOG.debug("Compiling storyboard %s to storyboardc %s", sourcePath, destinationPath);

      String compiledStoryboardFilename =
          Files.getNameWithoutExtension(destinationPath.toString()) + ".storyboardc";

      Path compiledStoryboardPath =
          destinationPath.getParent().resolve(compiledStoryboardFilename);
      stepsBuilder.add(
          new IbtoolStep(
              getProjectFilesystem(),
              ibtool.getEnvironment(getResolver()),
              ibtool.getCommandPrefix(getResolver()),
              ImmutableList.of("--compile"),
              sourcePath,
              compiledStoryboardPath));
    }
  }

  private void addResourceProcessingSteps(
      Path sourcePath,
      Path destinationPath,
      ImmutableList.Builder<Step> stepsBuilder) {
    String sourcePathExtension = Files.getFileExtension(sourcePath.toString())
        .toLowerCase(Locale.US);
    switch (sourcePathExtension) {
      case "plist":
      case "stringsdict":
        LOG.debug("Converting plist %s to binary plist %s", sourcePath, destinationPath);
        stepsBuilder.add(
            new PlistProcessStep(
                getProjectFilesystem(),
                sourcePath,
                destinationPath,
                ImmutableMap.<String, NSObject>of(),
                ImmutableMap.<String, NSObject>of(),
                PlistProcessStep.OutputFormat.BINARY));
        break;
      case "storyboard":
        addStoryboardProcessingSteps(sourcePath, destinationPath, stepsBuilder);
        break;
      case "xib":
        String compiledNibFilename = Files.getNameWithoutExtension(destinationPath.toString()) +
            ".nib";
        Path compiledNibPath = destinationPath.getParent().resolve(compiledNibFilename);
        LOG.debug("Compiling XIB %s to NIB %s", sourcePath, destinationPath);
        stepsBuilder.add(
            new IbtoolStep(
                getProjectFilesystem(),
                ibtool.getEnvironment(getResolver()),
                ibtool.getCommandPrefix(getResolver()),
                ImmutableList.of("--compile"),
                sourcePath,
                compiledNibPath));
        break;
      default:
        stepsBuilder.add(CopyStep.forFile(getProjectFilesystem(), sourcePath, destinationPath));
        break;
    }
  }

  @Override
  public boolean isTestedBy(BuildTarget testRule) {
    if (tests.contains(testRule)) {
      return true;
    }

    if (binary.isPresent()) {
      BuildRule binaryRule = binary.get();
      if (binaryRule instanceof NativeTestable) {
        return ((NativeTestable) binaryRule).isTestedBy(testRule);
      }
    }

    return false;
  }

  @Override
  public CxxPreprocessorInput getCxxPreprocessorInput(
      CxxPlatform cxxPlatform,
      HeaderVisibility headerVisibility) throws NoSuchBuildTargetException {
    if (binary.isPresent()) {
      BuildRule binaryRule = binary.get();
      if (binaryRule instanceof NativeTestable) {
        return ((NativeTestable) binaryRule).getCxxPreprocessorInput(
            cxxPlatform,
            headerVisibility);
      }
    }
    return CxxPreprocessorInput.EMPTY;
  }

  private boolean needCodeSign() {
    return binary.isPresent() && ApplePlatform.needsCodeSign(this.platformName);
  }

  @Override
  public AppleBundle getAppleBundle() {
    return this;
  }

  public Path getBundleRoot() {
    return bundleRoot;
  }

  public Path getBundleBinaryPath() {
    return bundleBinaryPath;
  }

  @Override
  public BuildRule getBinaryBuildRule() {
    return binary.get();
  }
}
