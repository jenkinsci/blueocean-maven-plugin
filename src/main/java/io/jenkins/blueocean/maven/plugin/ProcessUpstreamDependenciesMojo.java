package io.jenkins.blueocean.maven.plugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.annotation.Nonnull;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilderException;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.jenkinsci.maven.plugins.hpi.AbstractJenkinsMojo;
import org.jenkinsci.maven.plugins.hpi.MavenArtifact;

import net.sf.json.JSONObject;
import org.apache.commons.io.IOUtils;

/**
 * Goal which copies upstream Blue Ocean Javascript in an npm-compatible
 * structure locally
 */
@Mojo(name = "process-node-dependencies", defaultPhase = LifecyclePhase.GENERATE_RESOURCES)
public class ProcessUpstreamDependenciesMojo extends AbstractJenkinsMojo {
    /**
     * Location of the file.
     */
    @Parameter(defaultValue = "${project.basedir}", property = "baseDir", required = true)
    private File baseDir;

    /**
     * Location of the node_modules
     */
    @Parameter(defaultValue = "${project.basedir}/node_modules", property = "nodeModulesDir", required = false)
    private File nodeModulesDirectory;

    @Component
    protected DependencyGraphBuilder graphBuilder;

    /**
     * Execute upstream lookups
     */
    @Override
    public void execute() throws MojoExecutionException {
        List<MavenArtifact> artifacts = new ArrayList<>();
        try {
            collectBlueoceanDependencies(graphBuilder.buildDependencyGraph(project, null), artifacts);

            File nodeModulesOutputDir = nodeModulesDirectory;

            if (!nodeModulesOutputDir.exists()) {
                nodeModulesOutputDir.mkdirs();
            }

            for (MavenArtifact artifact : artifacts) {
                List<Contents> jarEntries = findJarEntries(artifact.getFile().toURI(), "package.json");

                getLog().debug("Using artifact: " + artifact.getArtifactId());

                JSONObject packageJson = JSONObject.fromObject(new String(jarEntries.get(0).data));

                String name = packageJson.getString("name");
                String[] subdirs = name.split("/");

                File outDir = nodeModulesDirectory;
                for (String subdir : subdirs) {
                    outDir = new File(outDir, subdir);
                }

                File artifactFile = artifact.getFile();
                long artifactLastModified = artifactFile.lastModified();

                if (!outDir.exists()) {
                    outDir.mkdirs();
                }

                try (ZipInputStream jar = new ZipInputStream(new FileInputStream(artifact.getFile()))) {
                    ZipEntry entry;
                    while ((entry = jar.getNextEntry()) != null) {
                        if (entry.isDirectory()) {
                            continue;
                        }
                        File outFile = new File(outDir, entry.getName());
                        if (!outFile.exists() || outFile.lastModified() < artifactLastModified) {
                            getLog().debug("Copying module: " + outFile.getAbsolutePath());
                            File parentFile = outFile.getParentFile();
                            if (!parentFile.exists()) {
                                parentFile.mkdirs();
                            }
                            try (FileOutputStream out = new FileOutputStream(outFile)) {
                                int read = 0;
                                byte[] buf = new byte[4096];
                                while ((read = jar.read(buf)) >= 0) {
                                    out.write(buf, 0, read);
                                }
                            }
                        }
                    }
                }
            }
        } catch (DependencyGraphBuilderException | IOException e) {
            throw new RuntimeException(e);
        }

        getLog().debug("Done installing blueocean dependencies for " + project.getArtifactId());
    }

    /**
     * Simple file as a byte array
     */
    private static class Contents {
        String fileName;
        byte[] data;
        
        Contents(@Nonnull String fileName, @Nonnull byte[] data) {
            this.fileName = fileName;
            this.data = data;
        }
    }

    /**
     * Finds jar entries matching a path glob, e.g. **\/META-INF/*.properties
     */
    @Nonnull
    private List<Contents> findJarEntries(@Nonnull URI jarFile, @Nonnull String pathGlob) throws IOException {
        URL jarUrl = jarFile.toURL();
        getLog().debug("Looking for " + pathGlob + " in " + jarFile + " with url: " + jarUrl);
        List<Contents> out = new ArrayList<>();
        Pattern matcher = Pattern.compile(
            ("\\Q" + pathGlob.replace("**", "\\E\\Q").replace("*", "\\E[^/]*\\Q").replace("\\E\\Q", "\\E.*\\Q") + "\\E").replace("\\Q\\E", "")
        );
        try (ZipInputStream jar = new ZipInputStream(jarUrl.openStream())) {
            for (ZipEntry entry; (entry = jar.getNextEntry()) != null;) {
                getLog().debug("Entry: " + entry.getName() + ", matches: " + matcher.matcher(entry.getName()).matches());
                if (matcher.matcher(entry.getName()).matches()) {
                    out.add(new Contents(entry.getName(), IOUtils.toByteArray(jar)));
                }
            }
        }
        return out;
    }

    /**
     * Collects all "blue ocean-like" upstream dependencies
     */
    private void collectBlueoceanDependencies(@Nonnull DependencyNode node, @Nonnull List<MavenArtifact> results) {
        MavenArtifact artifact = wrap(node.getArtifact());
        boolean isLocalProject = node.getArtifact().equals(project.getArtifact());
        try {
            if (!isLocalProject) { // not the local project
                getLog().debug("Testing artifact for Blue Ocean plugins: " + artifact.toString());
                List<Contents> jarEntries = findJarEntries(artifact.getFile().toURI(), "package.json");
                if (jarEntries.size() > 0) {
                    getLog().info("Adding upstream Blue Ocean plugin: " + artifact.toString());
                    results.add(artifact);
                }
            }
        } catch (IOException e) {
            getLog().warn("Unable to find artifact: " + artifact, e);

            MavenArtifact hpi = null;
            try {
                hpi = artifact.getHpi();
                if (hpi != null) {
                    List<Contents> jarEntries = findJarEntries(hpi.getFile().toURI(), "WEB-INF/lib/" + artifact.getArtifactId() + ".jar");
                    if (jarEntries.size() > 0) {
                        results.add(hpi);
                    }
                }
            } catch (IOException e2) {
                getLog().error("Unable to find hpi artifact for: " + hpi, e2);
            }
        }

        if (isLocalProject || !results.isEmpty()) { // only traverse up until we find a non-blue ocean project
            for (DependencyNode child : node.getChildren()) {
                collectBlueoceanDependencies(child, results);
            }
        }
    }
}
