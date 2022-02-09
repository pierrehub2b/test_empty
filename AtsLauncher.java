import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class AtsLauncher {

	//------------------------------------------------------------------------------------------------------------
	// AtsLauncher is a Java class used as a simple script file with Java >= 11
	// This script will try to update ATS tools and will launch ATS execution suites
	//------------------------------------------------------------------------------------------------------------

	private static String atsToolsServerUrl = "https://actiontestscript.com/";
	private static String atsToolsServerUrlPath = "tools/versions.php";

	private static String suiteFiles = "suite";
	private static String reportLevel = "1";
	private static String target = "target";
	private static String atsOutput = target + "/ats-output";

	private static final ArrayList<AtsToolEnvironment> atsToolsEnv = new ArrayList<AtsToolEnvironment>();
	private static final Path atsToolsPath = Paths.get(System.getProperty("user.home"), ".actiontestscript", "tools");

	private static String atsHomePath = null;
	private static String jdkHomePath = null;

	//------------------------------------------------------------------------------------------------------------
	// Main script execution
	//------------------------------------------------------------------------------------------------------------

	public static void main(String[] args) throws Exception, InterruptedException {

		String jenkinsToolsUrl = null;
		boolean buildEnvironment = false;

		for(int i=0; i<args.length; i++) {
			if(args[i].startsWith("-buildEnvironment")) {
				buildEnvironment = true;
			}else if(args[i].startsWith("-suiteXmlFiles=")) {
				suiteFiles = args[i].substring(15);
			}else if(args[i].startsWith("-ats-report=")) {
				reportLevel = args[i].substring(12);
			}else if(args[i].startsWith("-reportsDirectory=")) {
				atsOutput = args[i].substring(18);
			}else if(args[i].startsWith("-jenkinsUrl=")) {
				jenkinsToolsUrl = args[i].substring(12);
			}
		}

		deleteDirectory(Paths.get(target));
		deleteDirectory(Paths.get("test-output"));

		final List<String> envList = new ArrayList<String>();

		atsToolsEnv.add(new AtsToolEnvironment("ats"));
		atsToolsEnv.add(new AtsToolEnvironment("jasper"));
		atsToolsEnv.add(new AtsToolEnvironment("jdk"));

		if(jenkinsToolsUrl != null) {
			checkAtsToolsVersions(jenkinsToolsUrl, true, "userContent", "tools", "versions.csv");
		}else {
			checkAtsToolsVersions(atsToolsServerUrl, false, atsToolsServerUrlPath);
		}

		atsToolsEnv.stream().forEach(e -> installAtsTool(e, envList));

		if(buildEnvironment) {
			Path buildProperties = Paths.get("build.properties");
			Files.deleteIfExists(buildProperties);
			Files.write(buildProperties, String.join("\n", envList).getBytes(), StandardOpenOption.CREATE);
		}else {

			Map<String, String> userEnv = System.getenv();
			for (String envName : userEnv.keySet()) {
				envList.add(0, envName + "=" + userEnv.get(envName));
			}

			final String[] envArray = envList.toArray(new String[envList.size()]);
			final File currentDirectory = Paths.get("").toAbsolutePath().toFile();
			final Path generatedPath = Paths.get(target, "generated");
			final File generatedSourceDir = generatedPath.toFile();
			final String generatedSourceDirPath = generatedSourceDir.getAbsolutePath();

			generatedSourceDir.mkdirs();

			printLog("Current directory -> " + currentDirectory.getAbsolutePath());
			printLog("Generated java files -> " + generatedSourceDirPath);

			final FullLogConsumer logConsumer = new FullLogConsumer();

			final StringBuilder javaBuild = 
					new StringBuilder("\"")
					.append(Paths.get(jdkHomePath).toAbsolutePath().toString())
					.append("/bin/java");

			execute(new StringBuilder(javaBuild)
					.append("\" -cp ")
					.append(atsHomePath)
					.append("/libs/* com.ats.generator.Generator -prj \"")
					.append(currentDirectory.getAbsolutePath())
					.append("\" -dest " + target + "/generated -force -suites ")
					.append(suiteFiles),
					envArray, 
					currentDirectory,
					logConsumer,
					logConsumer);

			final ArrayList<String> files = listJavaClasses(generatedSourceDirPath.length() + 1, generatedSourceDir);

			final Path classFolder = Paths.get(target, "classes").toAbsolutePath();
			final Path classFolderAssets = classFolder.resolve("assets");
			classFolderAssets.toFile().mkdirs();

			copyFolder(Paths.get("src", "assets"), classFolderAssets);

			printLog("Compile classes to folder -> " + classFolder.toString());
			Files.write(generatedPath.resolve("JavaClasses.list"), String.join("\n", files).getBytes(), StandardOpenOption.CREATE);

			execute(new StringBuilder(javaBuild)
					.append("c\"") // javac
					.append(" -cp ")
					.append(atsHomePath)
					.append("/libs/* -d \"")
					.append(classFolder.toString())
					.append("\" @JavaClasses.list"),
					envArray, 
					generatedPath.toAbsolutePath().toFile(),
					logConsumer,
					logConsumer);

			printLog("Launch suite(s) execution -> " + suiteFiles);

			execute(new StringBuilder(javaBuild)
					.append("\"")
					.append(" -Dats-report=").append(reportLevel)
					.append(" -cp ").append(atsHomePath)
					.append("/libs/*")
					.append(File.pathSeparator)
					.append(target).append("/classes")
					.append(File.pathSeparator)
					.append("libs/* org.testng.TestNG")
					.append(" -d ").append(atsOutput)
					.append(" ").append(target).append("/suites.xml"),
					envArray, 
					currentDirectory,
					logConsumer,
					new TestNGLogConsumer());
			
			//---------------------------------------------------------------------------------------------------------------------------------------------------------------
			// Following line with output folder copy will be removed in a future release of Agilitest
			//---------------------------------------------------------------------------------------------------------------------------------------------------------------
			copyFolder(Paths.get(atsOutput), Paths.get("test-output"), p -> !p.getFileName().toString().endsWith(".atsv") && !p.getFileName().toString().endsWith(".jpeg"));
		}
	}

	//------------------------------------------------------------------------------------------------------------
	// Functions
	//------------------------------------------------------------------------------------------------------------

	private static Map<String, String[]> getServerToolsVersion(String server, String path){
		final Map<String, String[]> versions = new HashMap<String, String[]>();
		try {
			final URL url = new URL(server + path);
			final URLConnection yc = url.openConnection(); 
			final BufferedReader in = new BufferedReader(new InputStreamReader(yc.getInputStream()));

			String inputLine; 
			while ((inputLine = in.readLine()) != null) { 
				String[] lineData = inputLine.split(",");
				versions.put(lineData[0], lineData);
			} 
			in.close();

		} catch (IOException e) {}
		
		return versions;
	}

	private static void checkAtsToolsVersions(String server, boolean localServer, String ... path) {

		printLog("Using ATS tools server -> " + server);
		
		final Map<String, String[]> versions = getServerToolsVersion(server, String.join("/", path));

		if(versions.size() < 3 && localServer) {
			printLog("Unable to get all ATS tools on this server -> " + server);
			versions.putAll(getServerToolsVersion(atsToolsServerUrl, atsToolsServerUrlPath));
			if(versions.size() < 3) {
				printLog("Some ATS tools are missing, try to find them on local system ...");
				return;
			}
		}
		
		for (AtsToolEnvironment t : atsToolsEnv) {

			final String[] toolData = versions.get(t.name);

			final String folderName = toolData[2];
			t.folderName = folderName;

			final File toolFolder = atsToolsPath.resolve(folderName).toFile();
			if(toolFolder.exists()) {
				t.folder = toolFolder.getAbsolutePath();
			}else {
				t.url = toolData[3];
			}
		}
	}

	private static void installAtsTool(AtsToolEnvironment tool, List<String> envList) {
		if(tool.folderName == null) {

			final File[] files = atsToolsPath.toFile().listFiles();
			Arrays.sort(files, Comparator.comparingLong(File::lastModified));

			for(File f : files) {
				if(f.getName().startsWith(tool.name)) {
					tool.folderName = f.getName();
					tool.folder = f.getAbsolutePath();
					break;
				}
			}

		}else {
			try {
				final File tmpZipFile = Files.createTempDirectory("atsTool_").resolve( tool.folderName + ".zip").toAbsolutePath().toFile();
				if(tmpZipFile.exists()) {
					tmpZipFile.delete();
				}

				final HttpURLConnection con = (HttpURLConnection) new URL(tool.url).openConnection();

				IntConsumer consume;
				final int fileLength = con.getContentLength();
				if(fileLength == -1) {
					consume = (p) -> {System.out.println("Download [" + tool.name + "] -> " + p + " Mo");};
				}else {
					consume = (p) -> {System.out.println("Download [" + tool.name + "] -> " + p + " %");};
				}

				ReadableConsumerByteChannel rcbc = new ReadableConsumerByteChannel(
						Channels.newChannel(con.getInputStream()),
						fileLength,
						consume);

				final FileOutputStream fosx = new FileOutputStream(tmpZipFile);
				fosx.getChannel().transferFrom(rcbc, 0, Long.MAX_VALUE);
				fosx.close();

				byte[] buffer = new byte[1024];

				final ZipInputStream zis = new ZipInputStream(new FileInputStream(tmpZipFile));
				ZipEntry zipEntry = zis.getNextEntry();

				while (zipEntry != null) {
					final File newFile = newFile(atsToolsPath.toFile(), zipEntry);
					if(newFile != null && !newFile.exists()) {
						if (zipEntry.isDirectory()) {

							if (!newFile.isDirectory() && !newFile.mkdirs()) {
								throw new IOException("Failed to create directory " + newFile);
							}

						} else {

							final File parent = newFile.getParentFile();
							if (!parent.isDirectory() && !parent.mkdirs()) {
								throw new IOException("Failed to create directory " + parent);
							}

							final FileOutputStream fos = new FileOutputStream(newFile);
							int len;
							while ((len = zis.read(buffer)) > 0) {
								fos.write(buffer, 0, len);
							}
							fos.close();
						}
					}
					zis.closeEntry();
					zipEntry = zis.getNextEntry();
				}

				zis.close();

			} catch (IOException e) {
				//e.printStackTrace();
			}

			tool.folder = atsToolsPath.resolve(tool.folderName).toFile().getAbsolutePath();
		}

		if(tool.folder == null) {
			throw new RuntimeException("ATS tool is not installed on this system -> " + tool.name);
		}else {
			envList.add(tool.envName + "=" + tool.folder);
			printLog("Set environment variable [" + tool.envName + "] -> " + tool.folder);

			if("ats".equals(tool.name)) {
				atsHomePath = tool.folder;
			}else if("jdk".equals(tool.name)) {
				envList.add("JAVA_HOME=" + tool.folder);
				jdkHomePath = tool.folder;
			}
		}
	}

	//------------------------------------------------------------------------------------------------------------
	// Classes
	//------------------------------------------------------------------------------------------------------------

	private static class FullLogConsumer implements Consumer<String> {
		@Override
		public void accept(String s) {
			System.out.println(s);
		}
	}

	private static class TestNGLogConsumer implements Consumer<String> {
		@Override
		public void accept(String s) {
			System.out.println(
					s.replace("[TestNG]", "")
					.replace("[main] INFO org.testng.internal.Utils -", "[TestNG]")
					.replace("Warning: [org.testng.ITest]", "[TestNG] Warning :")
					.replace("[main] INFO org.testng.TestClass", "[TestNG]")
					);
		}
	}

	private static class StreamGobbler extends Thread {
		private InputStream inputStream;
		private Consumer<String> consumer;

		public StreamGobbler(InputStream inputStream, Consumer<String> consumer) {
			this.inputStream = inputStream;
			this.consumer = consumer;
		}

		@Override
		public void run() {
			new BufferedReader(new InputStreamReader(inputStream)).lines().forEach(consumer);
		}
	}

	private static class AtsToolEnvironment{

		public String name;
		public String envName;
		public String folder;
		public String folderName;

		public String url;

		public AtsToolEnvironment(String name) {
			this.name = name;
			this.envName = name.toUpperCase() + "_HOME";
		}
	}

	private static class ReadableConsumerByteChannel implements ReadableByteChannel {

		private final ReadableByteChannel rbc;
		private final IntConsumer onRead;
		private final int totalBytes;

		private int totalByteRead;

		private int currentPercent = 0;

		public ReadableConsumerByteChannel(ReadableByteChannel rbc, int totalBytes, IntConsumer onBytesRead) {
			this.rbc = rbc;
			this.totalBytes = totalBytes;
			this.onRead = onBytesRead;
		}

		@Override
		public int read(ByteBuffer dst) throws IOException {
			int nRead = rbc.read(dst);
			notifyBytesRead(nRead);
			return nRead;
		}

		protected void notifyBytesRead(int nRead){
			if(nRead<=0) {
				return;
			}
			totalByteRead += nRead;

			if(totalBytes != -1) {
				int percent = (int)(((float)totalByteRead/totalBytes)*100);
				if(percent % 5 == 0 && currentPercent != percent) {
					currentPercent = percent;
					onRead.accept(currentPercent);
				}
			}else if (totalByteRead % 10000 == 0) {
				onRead.accept(totalByteRead/10000);
			}
		}

		@Override
		public boolean isOpen() {
			return rbc.isOpen();
		}

		@Override
		public void close() throws IOException {
			rbc.close();
		}
	}

	//------------------------------------------------------------------------------------------------------------
	// Utils
	//------------------------------------------------------------------------------------------------------------

	private static void printLog(String data) {
		System.out.println("[ATS-LAUNCHER] " + data);
	}

	private static void execute(StringBuilder command, String[] envp, File currentDir, Consumer<String> outputConsumer, Consumer<String> errorConsumer) throws IOException, InterruptedException {

		final Process p = Runtime.getRuntime().exec(command.toString(), envp, currentDir);

		new StreamGobbler(p.getErrorStream(), errorConsumer).start();
		new StreamGobbler(p.getInputStream(), outputConsumer).start();

		p.waitFor();
	}

	//------------------------------------------------------------------------------------------------------------
	// Files
	//------------------------------------------------------------------------------------------------------------

	private static void copyFolder(Path src, Path dest, Predicate<Path> p) throws IOException {
		try (Stream<Path> stream = Files.walk(src)) {
			stream.filter(p).forEach(source -> copy(source, dest.resolve(src.relativize(source))));
		}
	}
	
	private static void copyFolder(Path src, Path dest) throws IOException {
		try (Stream<Path> stream = Files.walk(src)) {
			stream.forEach(source -> copy(source, dest.resolve(src.relativize(source))));
		}
	}

	private static void copy(Path source, Path dest) {
		try {
			Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
		} catch (Exception e) {
			throw new RuntimeException(e.getMessage(), e);
		}
	}

	private static ArrayList<String> listJavaClasses(int subLen, File directory) {

		final ArrayList<String> list = new ArrayList<String>();
		final File[] fList = directory.listFiles();

		if(fList == null) {
			throw new RuntimeException("Directory list files return null value ! (" + directory.getAbsolutePath() + ")");
		}else {
			for (File file : fList) {
				if (file.isFile()) {
					if(file.getName().endsWith(".java")) {
						list.add(file.getAbsolutePath().substring(subLen).replaceAll("\\\\", "/"));
					}
				} else if (file.isDirectory()) {
					list.addAll(listJavaClasses(subLen, file));
				}
			}
		}

		return list;
	}

	private static File newFile(File destinationDir, ZipEntry zipEntry) throws IOException {
		final File destFile = new File(destinationDir, zipEntry.getName());
		if (destFile.getCanonicalPath().startsWith(destinationDir.getCanonicalPath() + File.separator)) {
			return destFile;
		}
		return null;
	}

	private static void deleteDirectory(Path directory) throws IOException
	{
		if (Files.exists(directory))
		{
			Files.walkFileTree(directory, new SimpleFileVisitor<Path>()
			{
				@Override
				public FileVisitResult visitFile(Path path, BasicFileAttributes basicFileAttributes) throws IOException
				{
					Files.delete(path);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory(Path directory, IOException ioException) throws IOException
				{
					Files.delete(directory);
					return FileVisitResult.CONTINUE;
				}
			});
		}
	}
}