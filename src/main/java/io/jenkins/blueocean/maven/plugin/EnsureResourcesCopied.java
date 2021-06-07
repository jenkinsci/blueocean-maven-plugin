package io.jenkins.blueocean.maven.plugin;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.jenkinsci.maven.plugins.hpi.AbstractJenkinsMojo;

import java.io.File;
import java.nio.file.Files;

@Mojo(name = "package-blueocean-resources", defaultPhase = LifecyclePhase.PREPARE_PACKAGE)
public class EnsureResourcesCopied extends AbstractJenkinsMojo {
    /**
     * Location of the project dir
     */
    @Parameter(defaultValue = "${project.basedir}", property = "baseDir", required = true)
    private File baseDir;

    /**
     * Location of the output dir
     */
    @Parameter(defaultValue = "${project.build.outputDirectory}", property = "outputDirectory", required = true)
    private File outputDirectory;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            File packageJson = new File(baseDir, "package.json");
            if (packageJson.canRead()) {
                getLog().info("Adding package.json to " + outputDirectory.getCanonicalPath() + "/package.json");
                File out = new File(outputDirectory, "package.json");
                if (!out.exists() || packageJson.lastModified() > out.lastModified()) {
                    Files.copy(packageJson.toPath(), out.toPath());
                }
            }
        } catch(Exception e) {
            throw new MojoExecutionException("", e);
        }
    }
}
