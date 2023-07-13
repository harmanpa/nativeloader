package tech.cae.nativeloader;

import com.google.common.base.Splitter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.zeroturnaround.exec.InvalidExitValueException;
import org.zeroturnaround.exec.ProcessExecutor;

/**
 *
 * @author peter
 */
public class LibraryPackager {
    
    public static void main(String[] args) {
        for(String arg : args) {
            try {                
                writeDependencies(new File(arg), Arrays.asList("c", "stdc++", "gcc_s", "m", "pthread", "dl"));
            } catch(IOException ex) {
                ex.printStackTrace();
            }
        }
    }

    private static final Pattern SO_REGEX = Pattern.compile("^.*\\.so(\\.[0-9]+)*$");

    public static Set<File> getSharedLibraries(File directory) {
        Set<File> sharedLibraries = new LinkedHashSet<>();
        for(File sharedLibrary : directory.listFiles((File f) -> f.isFile() && (f.getName().endsWith(".dll") || SO_REGEX.matcher(f.getName()).matches()))) {
            sharedLibraries.add(sharedLibrary);
        }
        return sharedLibraries;
    }

    public static void writeDependencies(File directory) throws IOException {
        writeDependencies(directory, Arrays.asList());
    }

    public static void writeDependencies(File directory, List<String> excluded) throws IOException {
        Set<File> sharedLibraries = getSharedLibraries(directory);
        Map<String, String> absoluteNames = new HashMap<>();
        getAbsoluteNames(sharedLibraries, absoluteNames);
        for (File sharedLibrary : sharedLibraries) {
            String deps = getDependents(sharedLibrary)
                .filter(name -> !excluded.contains(shortName(name)))
                .map(name -> absoluteNames.getOrDefault(name, name))
                .reduce("", (String a, String b) -> a.isEmpty() ? b : a + "\n" + b);
            System.out.println("Dependents of " + sharedLibrary.getAbsolutePath() + ":\n\t" + deps.replaceAll("\n", "\n\t"));
            Files.writeString(new File(directory, sharedLibrary.getName() + ".deps").toPath(), deps, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
    }

    static String shortName(String name) {
        if(name.indexOf('.')>-1) {
            name = name.substring(0, name.indexOf('.'));
        }
        if(name.startsWith("lib")) {
            name = name.substring(3);
        }
        return name;
    }

    static void getAbsoluteNames(Set<File> libraries, Map<String, String> nameMapping) {
        // Sort longest name first so we ensure .so files map to their implementation
        libraries.stream()
            .sorted((File a, File b) -> -Integer.compare(a.getName().length(), b.getName().length()))
            .forEach((File f) -> getAbsoluteNames(f.getName(), f.getName(), nameMapping));
    }

    static void getAbsoluteNames(String absolute, String library, Map<String, String> nameMapping) {
        nameMapping.putIfAbsent(library, absolute);
        // If we are on Linux, then libXXX.so.1.0 == libXXX.so.1 == libXXX.so
        try {
            Integer.parseInt(library.substring(library.lastIndexOf('.')+1));
            getAbsoluteNames(absolute, library.substring(0, library.lastIndexOf('.')), nameMapping);
        } catch(NumberFormatException ex) {
        }
    }

    static Stream<String> parse(String response, Pattern lineRegex) {
        return Splitter.on('\n').splitToStream(response)
                .map(line -> lineRegex.matcher(line))
                .filter(m -> m.matches())
                .map(m -> m.group(1));
    }

    public static Stream<String> getDependents(File dll) throws IOException {
        boolean isWindows = System.getProperty("os.name")
                .toLowerCase().startsWith("windows");
        List<String> commands = new ArrayList<>();
        if (isWindows) {
            commands.addAll(Arrays.asList("cmd", "/c", "dumpbin", "/DEPENDENTS", dll.getAbsolutePath()));
        } else {
            commands.addAll(Arrays.asList("ldd", dll.getAbsolutePath()));
            // commands.addAll(Arrays.asList("/bin/sh", "-c", "\"ldd " + dll.getAbsolutePath() + "\""));
        }
        try {
            String output = new ProcessExecutor().command(commands)
                    .readOutput(true).execute()
                    .outputUTF8();
            if (isWindows) {
                return parse(output, Pattern.compile("^\\s*([^\\s\\.\\\\\\/]+\\.dll)\\s*$"));
            } else {
                return parse(output, Pattern.compile("^\\s*([^\\s\\\\\\/]+) => (?>[^\\s]+\\s\\(0x[0-9a-f]+\\)|not found)$"));
            }
        } catch (IOException | InterruptedException | TimeoutException | InvalidExitValueException ex) {
            throw new IOException("Failed determining library dependencies", ex);
        }
    }
}
