/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.jooby;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.apache.maven.Maven;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.jooby.hotreload.Watcher;

import javaslang.control.Try;

@Mojo(name = "run", threadSafe = true, requiresDependencyResolution = ResolutionScope.TEST)
@Execute(phase = LifecyclePhase.TEST_COMPILE)
public class JoobyMojo extends AbstractMojo {

  private static class ShutdownHook extends Thread {
    private Log log;

    private List<Command> commands;

    private Watcher watcher;

    public ShutdownHook(final Log log, final List<Command> commands) {
      this.log = log;
      this.commands = commands;
      setDaemon(true);
    }

    @Override
    public void run() {
      if (watcher != null) {
        log.info("stopping: watcher");
        Try.run(watcher::stop).onFailure(ex -> log.debug("Stop of watcher resulted in error", ex));
      }
      commands.forEach(cmd -> {
        log.info("stopping: " + cmd);
        Try.run(cmd::stop).onFailure(ex -> log.error("Stop of " + cmd + " resulted in error", ex));
      });
    }
  }

  @Component
  private MavenProject mavenProject;

  @Parameter(defaultValue = "${session}", required = true, readonly = true)
  protected MavenSession session;

  @Parameter(property = "main.class", defaultValue = "${application.class}")
  protected String mainClass;

  @Parameter(defaultValue = "${project.build.outputDirectory}")
  private String buildOutputDirectory;

  @Parameter(property = "jooby.commands")
  private List<ExternalCommand> commands;

  @Parameter(property = "jooby.vmArgs")
  private List<String> vmArgs;

  @Parameter(property = "jooby.includes")
  private List<String> includes;

  @Parameter(property = "jooby.excludes")
  private List<String> excludes;

  @Parameter(property = "application.debug", defaultValue = "true")
  private String debug;

  @Parameter(defaultValue = "${plugin.artifacts}")
  private List<org.apache.maven.artifact.Artifact> pluginArtifacts;

  @Parameter(property = "compiler", defaultValue = "on")
  private String compiler;

  @Component
  protected Maven maven;

  @Parameter(property = "application.fork", defaultValue = "false")
  private boolean fork = false;

  @SuppressWarnings("unchecked")
  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {

    boolean js = new File("app.js").exists();
    if (js) {
      mainClass = "org.jooby.Jooby";
    }

    Set<File> appcp = new LinkedHashSet<File>();

    // public / config, etc..
    appcp.addAll(resources(mavenProject.getResources()));

    // target/classes
    appcp.add(new File(buildOutputDirectory));

    // *.jar
    Set<Artifact> artifacts = new LinkedHashSet<Artifact>(mavenProject.getArtifacts());

    artifacts.forEach(artifact -> {
      if (!"pom".equals(artifact.getType())) {
        appcp.add(new File(artifact.getFile().getAbsolutePath()));
      }
    });

    Set<File> classpath = new LinkedHashSet<>();

    File hotreload = extra(pluginArtifacts, "jooby-run").get();
    File jbossModules = extra(pluginArtifacts, "jboss-modules").get();
    classpath.add(hotreload);
    classpath.add(jbossModules);

    // prepare commands
    List<Command> cmds = new ArrayList<>();
    if (commands != null && commands.size() > 0) {
      cmds.addAll(this.commands);
    }

    // includes/excludes pattern
    String includes = null;
    if (this.includes != null && this.includes.size() > 0) {
      includes = this.includes.stream().collect(Collectors.joining(":"));
    }
    String excludes = null;
    if (this.excludes != null && this.excludes.size() > 0) {
      excludes = this.excludes.stream().collect(Collectors.joining(":"));
    }
    // moduleId
    String mId = mavenProject.getGroupId() + "." + mavenProject.getArtifactId();

    // logback and application.version
    setLogback();
    System.setProperty("application.version", mavenProject.getVersion());

    // fork?
    Command runapp = fork
        ? new RunForkedApp(mavenProject.getBasedir(), debug, vmArgs, classpath, mId, mainClass,
            appcp, includes, excludes)
        : new RunApp(mId, mainClass, appcp, includes, excludes);

    // run app at the end
    cmds.add(runapp);

    for (Command cmd : cmds) {
      cmd.setWorkdir(mavenProject.getBasedir());
      getLog().debug("cmd: " + cmd.debug());
    }

    Watcher watcher = setupCompiler(mavenProject, compiler, goal -> {
      maven.execute(DefaultMavenExecutionRequest.copy(session.getRequest())
          .setGoals(Arrays.asList(goal)));
    });

    ShutdownHook shutdownHook = new ShutdownHook(getLog(), cmds);
    shutdownHook.watcher = watcher;
    /**
     * Shutdown hook
     */
    Runtime.getRuntime().addShutdownHook(shutdownHook);

    if (watcher != null) {
      watcher.start();
    }

    /**
     * Start process
     */
    for (Command cmd : cmds) {
      try {
        getLog().debug("Starting process: " + cmd.debug());
        cmd.execute();
      } catch (Exception ex) {
        throw new MojoFailureException("Execution of " + cmd + " resulted in error", ex);
      }
    }

  }

  @SuppressWarnings("unchecked")
  private static Watcher setupCompiler(final MavenProject project, final String compiler,
      final Consumer<String> task) throws MojoFailureException {
    File eclipseClasspath = new File(project.getBasedir(), ".classpath");
    if ("off".equalsIgnoreCase(compiler) || eclipseClasspath.exists()) {
      return null;
    }
    List<File> resources = resources(project.getResources());
    resources.add(0, new File(project.getBuild().getSourceDirectory()));
    List<Path> paths = resources.stream()
        .filter(File::exists)
        .map(File::toPath)
        .collect(Collectors.toList());
    try {
      return new Watcher((kind, path) -> {
        if (path.toString().endsWith(".java")) {
          task.accept("compile");
        } else if (path.toString().endsWith(".conf")
            || path.toString().endsWith(".properties")) {
          task.accept("compile");
        }
      }, paths.toArray(new Path[paths.size()]));
    } catch (Exception ex) {
      throw new MojoFailureException("Can't compile source code", ex);
    }
  }

  private void setLogback() {
    // logback
    File[] logbackFiles = {localFile("conf", "logback-test.xml"),
        localFile("conf", "logback.xml") };
    for (File logback : logbackFiles) {
      if (logback.exists()) {
        System.setProperty("logback.configurationFile", logback.getAbsolutePath());
        break;
      }
    }
  }

  private File localFile(final String... paths) {
    File result = mavenProject.getBasedir();
    for (String path : paths) {
      result = new File(result, path);
    }
    return result;
  }

  private static List<File> resources(final Iterable<Resource> resources) {
    List<File> result = new ArrayList<>();
    for (Resource resource : resources) {
      String dir = resource.getDirectory();
      File file = new File(dir);
      if (file.exists()) {
        result.add(file);
      }
    }
    return result;
  }

  private Optional<File> extra(final List<Artifact> artifacts, final String name) {
    for (Artifact artifact : artifacts) {
      for (String tail : artifact.getDependencyTrail()) {
        if (tail.contains(name)) {
          return Optional.of(artifact.getFile());
        }
      }
    }
    return Optional.empty();
  }

}
