package cioran;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

public class Cioran {
	public final static String MAVEN = "/usr/bin/mvn";

	void run(Path clientJar, String groupId, String artifactId, String v1, String v2) {
		try {
			Path extractDest = Paths.get(clientJar.getFileName().toString());

			// Step 1: extract the content of the client JAR locally
			extractJAR(clientJar, extractDest);
			System.out.println("Extracted " + clientJar.toAbsolutePath() + " to " + extractDest.toAbsolutePath());

			// Step 2: Move its pom.xml file to the root of the dest folder
			Path pomFile = movePOM(extractDest);
			System.out.println("Moved extracted pom.xml to " + pomFile.toAbsolutePath());

			// Step 3: Update the pom.xml file to enable compilation with
			// the updated version of the chosen library
			updatePOM(pomFile, groupId, artifactId, v1, v2);
			System.out.println("Updated POM file at " + pomFile.toAbsolutePath());

			// Step 4: Run Maven and record the output
			List<String> compilationErrors = runMaven(pomFile);
			System.out.println(String.join("\n", compilationErrors));
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void extractJAR(Path jar, Path dest) throws IOException {
		if (!dest.toFile().exists())
			dest.toFile().mkdir();

		String destPath = dest.toAbsolutePath().toString();
		JarFile jarFile = new JarFile(jar.toAbsolutePath().toString());
		Enumeration<JarEntry> enumEntries = jarFile.entries();

		while (enumEntries.hasMoreElements()) {
			JarEntry file = (JarEntry) enumEntries.nextElement();
			File f = new File(destPath + File.separator + file.getName());

			if (file.isDirectory()) {
				f.mkdir();
				continue;
			}

			InputStream is = jarFile.getInputStream(file);
			FileOutputStream fos = new FileOutputStream(f);
			while (is.available() > 0) {
				fos.write(is.read());
			}
			fos.close();
			is.close();
		}
		jarFile.close();
	}

	private Path movePOM(Path dest) throws IOException {
		// FIXME: This assumes there's one and only one pom.xml file...
		try (Stream<Path> walk = Files.walk(dest)) {
			Path oldPom = walk.filter(f -> f.getFileName().toString().equals("pom.xml")).findFirst().get();
			Path newPom = dest.resolve("pom.xml");

			return Files.copy(oldPom, newPom, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private void updatePOM(Path pomFile, String groupId, String artifactId, String v1, String v2)
			throws IOException, XmlPullParserException {
		try (Reader reader = new FileReader(pomFile.toAbsolutePath().toString())) {
			MavenXpp3Reader pomReader = new MavenXpp3Reader();

			Model model = pomReader.read(reader);
			reader.close();

			Build modelBuild = model.getBuild();
			if (modelBuild == null) {
				model.setBuild(new Build());
			}

			// Step 1: insert maven-dependency-plugin
			Plugin mdpPlugin = buildMdpPlugin(model.getGroupId(), model.getArtifactId(), model.getVersion());
			model.getBuild().addPlugin(mdpPlugin);

			// Step 2: insert build-helper-maven-plugin
			Plugin bhmPlugin = buildBhmPlugin();
			model.getBuild().addPlugin(bhmPlugin);

			// Step 3: increase version number for the library
			// FIXME: This assumes it exists...
			for (Dependency d : model.getDependencies()) {
				if (d.getGroupId().equals(groupId) && d.getArtifactId().equals(artifactId)) {
					System.out.println("Upgrading " + d + " to " + v2);
					d.setVersion(v2);
					break;
				}
			}

			MavenXpp3Writer pomWriter = new MavenXpp3Writer();
			pomWriter.write(new FileWriter(pomFile.toAbsolutePath().toString()), model);
		}
	}

	private List<String> runMaven(Path pomFile) throws IOException, InterruptedException {
		List<String> errors = new ArrayList<>();
		ProcessBuilder pb = new ProcessBuilder(MAVEN, "clean", "compile");
		pb.directory(pomFile.getParent().toFile());

		Process process = pb.start();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
			String currentLine;

			// FIXME: Ugly as fuck
			boolean recording = false;
			while ((currentLine = reader.readLine()) != null) {
				if (currentLine.contains("Finished at:"))
					recording = true;
				
				if (currentLine.startsWith("[ERROR] -> [Help 1]"))
					recording = false;

				if (recording) {
					if (currentLine.startsWith("[ERROR] ") && !currentLine.equals("[ERROR] ")) {
						errors.add(currentLine);
					}
				}
			}
		}

		int status = process.waitFor();
		System.out.println("Maven build finished with exit status " + status);

		return errors;
	}

	private Plugin buildMdpPlugin(String groupId, String artifactId, String version)
			throws XmlPullParserException, IOException {
		Plugin mdp = new Plugin();
		mdp.setGroupId("org.apache.maven.plugins");
		mdp.setArtifactId("maven-dependency-plugin");
		mdp.setVersion("3.1.1");

		PluginExecution mdpExec = new PluginExecution();
		mdpExec.setId("unpack");
		mdpExec.setPhase("process-sources");
		mdpExec.addGoal("unpack");

		StringBuilder configString = new StringBuilder().append("<configuration><artifactItems><artifactItem>")
				.append("<groupId>" + groupId + "</groupId>").append("<artifactId>" + artifactId + "</artifactId>")
				.append("<version>" + version + "</version>").append("<classifier>sources</classifier>")
				.append("<overWrite>true</overWrite>")
				.append("<outputDirectory>${project.build.directory}/extracted-sources</outputDirectory>")
				.append("</artifactItem></artifactItems></configuration>");

		Xpp3Dom config = Xpp3DomBuilder.build(new StringReader(configString.toString()));
		mdpExec.setConfiguration(config);

		mdp.addExecution(mdpExec);

		return mdp;
	}

	private Plugin buildBhmPlugin() throws XmlPullParserException, IOException {
		Plugin mdp = new Plugin();
		mdp.setGroupId("org.codehaus.mojo");
		mdp.setArtifactId("build-helper-maven-plugin");
		mdp.setVersion("3.0.0");

		PluginExecution mdpExec = new PluginExecution();
		mdpExec.setId("add-source");
		mdpExec.setPhase("generate-sources");
		mdpExec.addGoal("add-source");

		StringBuilder configString = new StringBuilder().append("<configuration><sources>")
				.append("<source>${project.build.directory}/extracted-sources</source>")
				.append("</sources></configuration>");

		Xpp3Dom config = Xpp3DomBuilder.build(new StringReader(configString.toString()));
		mdpExec.setConfiguration(config);

		mdp.addExecution(mdpExec);

		return mdp;
	}

	public static void main(String[] args) {
		Path clientJar = Paths.get("data/guava-client-0.0.1.jar");
		String libGroupId = "com.google.guava";
		String libArtifactId = "guava";
		String v1 = "17.0";
		String v2 = "18.0";

		Cioran c = new Cioran();
		c.run(clientJar, libGroupId, libArtifactId, v1, v2);
	}
}
