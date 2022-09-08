package io.jenkins.blueocean.maven.plugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.io.IOUtils;
import org.apache.maven.artifact.Artifact;
import edu.umd.cs.findbugs.annotations.NonNull;
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
        // Skip non-js projects
        if (!new File(baseDir, "package.json").canRead()) {
            getLog().info("Skipping blueocean dependency install for non-js project: " + project.getArtifactId());
            return;
        }

        long start = System.currentTimeMillis();
        List<MavenArtifact> artifacts = new ArrayList<>();
        try {
            collectBlueoceanDependencies(graphBuilder.buildDependencyGraph(project, null), artifacts);

            if (artifacts.isEmpty()) {
                getLog().info("No upstream blueocean dependencies found for: " + project.getArtifactId());
                return;
            }

            File nodeModulesOutputDir = nodeModulesDirectory;

            if (!nodeModulesOutputDir.exists()) {
                if (!nodeModulesOutputDir.mkdirs()) {
                    throw new MojoExecutionException("Unable to make node_modules directory: " + nodeModulesOutputDir.getCanonicalPath());
                }
            }

            getLog().info("Installing upstream dependencies...");

            for (MavenArtifact artifact : artifacts) {
                List<Contents> jarEntries = findJarEntries(artifact.getFile().toURI(), "package.json");

                JSONObject packageJson = JSONObject.fromObject(new String(jarEntries.get(0).data, StandardCharsets.UTF_8 ));

                String name = packageJson.getString("name");
                String[] subdirs = name.split("/");

                File outDir = nodeModulesDirectory;
                for (String subdir : subdirs) {
                    outDir = new File(outDir, subdir);
                }

                File artifactFile = artifact.getFile();
                long artifactLastModified = artifactFile.lastModified();

                if (!outDir.exists()) {
                    if (!outDir.mkdirs()) {
                        throw new MojoExecutionException("Unable to make module output directory: " + outDir.getCanonicalPath());
                    }
                }

                int read;
                byte[] buf = new byte[1024*8];

                try (ZipInputStream jar = new ZipInputStream(new FileInputStream(artifact.getFile()))) {
                    ZipEntry entry;
                    while ((entry = jar.getNextEntry()) != null) {
                        if (entry.isDirectory()) {
                            continue;
                        }
                        File outFile = new File(outDir, entry.getName());
                        if (!outFile.toPath().normalize().startsWith(outDir.toPath().normalize())) {
                            throw new IOException("Bad zip entry");
                        }
                        if (!outFile.exists() || outFile.lastModified() < artifactLastModified) {
                            if (getLog().isDebugEnabled()) getLog().debug("Copying file: " + outFile.getAbsolutePath());
                            File parentFile = outFile.getParentFile();
                            if (!parentFile.exists()) {
                                if (!parentFile.mkdirs()) {
                                    throw new MojoExecutionException("Unable to make parent directory for: " + outFile.getCanonicalPath());
                                }
                            }
                            try (FileOutputStream out = new FileOutputStream(outFile)) {
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

        getLog().info("Done installing blueocean dependencies for " + project.getArtifactId() + " in " + (System.currentTimeMillis() - start) + "ms");
    }

    /**
     * Simple file as a byte array
     */
    protected static class Contents {
        public final String fileName;
        public final byte[] data;
        
        Contents( @NonNull String fileName, @NonNull byte[] data) {
            this.fileName = fileName;
            this.data = data;
        }
    }

    /**
     * Finds jar entries matching a path glob, e.g. **\/META-INF/*.properties
     */
    @NonNull
    private List<Contents> findJarEntries(@NonNull URI jarFile, @NonNull String pathGlob) throws IOException {
        URL jarUrl = jarFile.toURL();
        if (getLog().isDebugEnabled()) getLog().debug("Looking for " + pathGlob + " in " + jarFile + " with url: " + jarUrl);
        List<Contents> out = new ArrayList<>();
        Pattern matcher = Pattern.compile(
            ("\\Q" + pathGlob.replace("**", "\\E\\Q").replace("*", "\\E[^/]*\\Q").replace("\\E\\Q", "\\E.*\\Q") + "\\E").replace("\\Q\\E", "")
        );
        try (ZipInputStream jar = new ZipInputStream(jarUrl.openStream())) {
            for (ZipEntry entry; (entry = jar.getNextEntry()) != null;) {
                if (getLog().isDebugEnabled()) getLog().debug("Entry: " + entry.getName() + ", matches: " + matcher.matcher(entry.getName()).matches());
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
    private void collectBlueoceanDependencies(@NonNull DependencyNode node, @NonNull List<MavenArtifact> results) {
        MavenArtifact artifact = wrap(node.getArtifact());
        boolean isLocalProject = node.getArtifact().equals(project.getArtifact());
        try {
            if (!isLocalProject) { // not the local project
                //filtering system they cannot contains package.json files
                if(! artifact.getScope().equals(Artifact.SCOPE_SYSTEM)) {
                    if (getLog().isDebugEnabled()) getLog().debug("Testing artifact for Blue Ocean plugins: " + artifact.toString());
                    List<Contents> jarEntries = findJarEntries(artifact.getFile().toURI(), "package.json");
                    if (jarEntries.size() > 0) {
                        getLog().info("Adding upstream Blue Ocean plugin: " + artifact.toString());
                        results.add(artifact);
                    }
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
