/*******************************************************************************
 * Black Duck Software Suite SDK
 * Copyright (C) 2016 Black Duck Software, Inc.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 *******************************************************************************/
package com.blackducksoftware.integration.gradle;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.ResolvedDependency;
import org.gradle.api.tasks.TaskAction;

import com.blackducksoftware.integration.build.BuildArtifact;
import com.blackducksoftware.integration.build.BuildDependency;
import com.blackducksoftware.integration.build.BuildInfo;
import com.google.gson.Gson;

public class BuildInfoCustomTask extends DefaultTask {
	private final GradleUtil gradleUtil = new GradleUtil();

	private File blackDuckDir;

	public File getBlackDuckDir() {
		return blackDuckDir;
	}

	@TaskAction
	public void gatherDeps() throws IOException {
		final Project project = getProject();

		final File buildDir = gradleUtil.findBuildDir(project);

		blackDuckDir = new File(buildDir, "BlackDuck/");
		blackDuckDir.mkdirs();

		System.out.println(blackDuckDir.getCanonicalPath());

		BuildInfo oldBuildInfo = null;
		final File file = new File(blackDuckDir.getCanonicalPath() + File.separator + BuildInfo.OUTPUT_FILE_NAME);
		if (file.exists()) {
			// Read in the old build-info
			// if it has the same build id, its probably a Gradle build with
			// multiple projects
			// so we add to the dependencies, instead of creating a new
			// build-info.json

			final StringBuilder buildInfoStringBuilder = new StringBuilder();
			BufferedReader br = null;
			try {
				// We read the build-info.json file into a StringBuilder
				br = new BufferedReader(new FileReader(file));
				String line;
				while ((line = br.readLine()) != null) {
					buildInfoStringBuilder.append(line);
				}
			} finally {
				if (br != null) {
					br.close();
				}
			}

			// We use the StringBuilder to make a single json String from the
			// file contents
			final String buildInfoString = buildInfoStringBuilder.toString();
			if (buildInfoString != null && buildInfoString.trim().length() > 0) {
				// build-info.json is not an empty file
				final Gson gson = new Gson();
				// We use Gson to turn the json string into a BuildInfo object
				oldBuildInfo = gson.fromJson(buildInfoString, BuildInfo.class);
			}
		}
		final String buildId = System.getProperty(GradleUtil.BUILD_ID_PROPERTY);
		System.out.println("BUILD ID : " + buildId);

		BuildInfo buildInfo = null;
		if (oldBuildInfo != null && oldBuildInfo.getBuildId().equals(buildId)) {
			// This must be a sub project in a multi-project gradle Build
			System.out.println("Will add to the build-info.json file");

			buildInfo = oldBuildInfo;
		} else {
			// Either this is the first time the project is being built, there
			// was no build-info.json file OR
			// the buildId did not match in which case the build-info.json is
			// from a different Build
			System.out.println("Will create build-info.json file");

			buildInfo = new BuildInfo();
			buildInfo.setBuildId(buildId);

			final BuildArtifact buildArtifact = new BuildArtifact();
			buildArtifact.setType("org.gradle");
			buildArtifact.setGroup(project.getGroup().toString());
			buildArtifact.setArtifact(project.getName());
			buildArtifact.setVersion(project.getVersion().toString());

			buildInfo.setBuildArtifact(buildArtifact);
		}

		final Map<String, BuildDependency> resolvedDependenciesMap = new HashMap<String, BuildDependency>();
		if (buildInfo.getDependencies() != null && !buildInfo.getDependencies().isEmpty()) {
			for (final BuildDependency dependency : buildInfo.getDependencies()) {
				// Adding previously discovered dependencies to the new map
				final String externalId = dependency.getId();
				resolvedDependenciesMap.put(externalId, dependency);
			}
			buildInfo.setDependencies(new HashSet<BuildDependency>());
		}

		final ScopesManager scopesManager = new ScopesManager(project);
		final Set<Configuration> configurations = project.getConfigurations();
		for (final Configuration configuration : configurations) {
			final Set<ResolvedDependency> dependencies = configuration.getResolvedConfiguration()
					.getFirstLevelModuleDependencies();
			final String scope = configuration.getName();
			for (final ResolvedDependency dependency : dependencies) {
				if (dependency.getAllModuleArtifacts().size() > 0) {
					for (final ResolvedArtifact artifact : dependency.getAllModuleArtifacts()) {
						final ModuleVersionIdentifier id = artifact.getModuleVersion().getId();
						final String groupId = id.getGroup();
						final String artifactId = id.getName();
						final String version = id.getVersion();
						addDependency(resolvedDependenciesMap, scope, groupId, artifactId, version, scopesManager);
					}
				} else {
					final String groupId = dependency.getModuleGroup();
					final String artifactId = dependency.getModuleName();
					final String version = dependency.getModuleVersion();
					addDependency(resolvedDependenciesMap, scope, groupId, artifactId, version, scopesManager);
				}
			}
		}
		final Set<BuildDependency> dependencies = new HashSet<BuildDependency>();
		dependencies.addAll(resolvedDependenciesMap.values());
		buildInfo.setDependencies(dependencies);
		buildInfo.close(blackDuckDir);

		final DependencyGatherer dependencyGatherer = new DependencyGatherer(project, scopesManager, blackDuckDir);
		dependencyGatherer.handleBdioOutput();
	}

	private void addDependency(final Map<String, BuildDependency> dependenciesMap, final String scope,
			final String group, final String artifact, final String version, final ScopesManager scopesManager) {
		final String externalId = group + ":" + artifact + ":" + version;
		if (dependenciesMap.containsKey(externalId)) {
			final BuildDependency existing = dependenciesMap.get(externalId);
			if (!existing.getScopes().contains(scope)) {
				existing.getScopes().add(scope);
			}
		} else {
			if (scopesManager.shouldIncludeScope(scope)) {
				final BuildDependency buildDependency = new BuildDependency();
				buildDependency.setGroup(group);
				buildDependency.setArtifact(artifact);
				buildDependency.setVersion(version);

				final Set<String> scopeList = new HashSet<String>();
				scopeList.add(scope);
				buildDependency.setScopes(scopeList);
				dependenciesMap.put(externalId, buildDependency);
			}
		}
	}

}