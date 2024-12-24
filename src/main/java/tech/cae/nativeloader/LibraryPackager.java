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
import java.util.stream.Collectors;
import org.zeroturnaround.exec.InvalidExitValueException;
import org.zeroturnaround.exec.ProcessExecutor;

/**
 *
 * @author peter
 */
public class LibraryPackager {

    public static void main(String[] args) {
        for (String arg : args) {
            try {
                writeDependencies(new File(arg), Arrays.asList(
                        "c",
                        "stdc++",
                        "gcc_s",
                        "gcc_s_seh",
                        "m",
                        "pthread",
                        "winpthread",
                        "dl",
                        "KERNEL32",
                        "api-ms-win-*"
                ));
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }

    private static final Pattern SO_REGEX = Pattern.compile("^.*\\.so(\\.[0-9]+)*$");

    public static Set<File> getSharedLibraries(File directory) {
        boolean isWindows = System.getProperty("os.name")
                .toLowerCase().startsWith("windows");
        Set<File> sharedLibraries = new LinkedHashSet<>();
        sharedLibraries.addAll(Arrays.asList(directory.listFiles(
                (File f) -> f.isFile() && ((isWindows && f.getName().endsWith(".dll"))
                || (!isWindows && SO_REGEX.matcher(f.getName()).matches())))));
        return sharedLibraries;
    }

    public static void writeDependencies(File directory) throws IOException {
        writeDependencies(directory, Arrays.asList());
    }

    public static void writeDependencies(File directory, List<String> excluded) throws IOException {
        Set<File> sharedLibraries = getSharedLibraries(directory);
        Map<String, String> absoluteNames = new HashMap<>();
        getAbsoluteNames(sharedLibraries, absoluteNames);
        Set<String> allSearchPaths = new LinkedHashSet<>();
        for (File sharedLibrary : sharedLibraries) {
            // Find the dependencies of a library
            Map<String, String> searchPaths = new HashMap<>();
            List<String> depLibraries = getDependents(sharedLibrary, searchPaths)
                    .filter(name -> !nameMatches(name, excluded))
                    .map(name -> absoluteNames.getOrDefault(name, name))
                    .collect(Collectors.toList());
            // Check the found search paths to exclude files that exist locally
            for (Map.Entry<String, String> searchPath : searchPaths.entrySet()) {
                String absName = absoluteNames.getOrDefault(searchPath.getKey(), searchPath.getKey());
                if (!new File(directory, absName).exists()) {
                    allSearchPaths.add(searchPath.getValue());
                }
            }
            String deps = depLibraries.stream().reduce("", (String a, String b) -> a.isEmpty() ? b : a + "\n" + b);
            System.out.println(
                    "Dependents of " + sharedLibrary.getAbsolutePath() + ":\n\t" + deps.replaceAll("\n", "\n\t"));
            Files.writeString(new File(directory, sharedLibrary.getName() + ".deps").toPath(), deps,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
        Files.writeString(new File(directory, "searchpaths").toPath(),
                allSearchPaths.stream().reduce("", (String a, String b) -> a.isEmpty() ? b : a + "\n" + b),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    static boolean nameMatches(String name, List<String> pattern) {
        return pattern.stream().anyMatch(p -> nameMatches(name, p));
    }

    static boolean nameMatches(String name, String pattern) {
        String shortName = shortName(name);
        if (pattern.contains("*")) {
            return Pattern.compile(pattern.replace("*", ".*")).matcher(shortName).matches();
        } else {
            return pattern.equals(shortName);
        }
    }

    static String shortName(String name) {
        if (name.indexOf('.') > -1) {
            name = name.substring(0, name.indexOf('.'));
        }
        if (name.startsWith("lib")) {
            name = name.substring(3);
        }
        return removeVersionNumber(name);
    }

    static String removeVersionNumber(String name) {
        if (name.indexOf('-') > -1) {
            String number = name.substring(name.lastIndexOf('-') + 1);
            if (number.length() > 0 && number.chars().allMatch(c -> Character.isDigit(c))) {
                return name.substring(0, name.lastIndexOf('-'));
            }
        }
        return name;
    }

    static void getAbsoluteNames(Set<File> libraries, Map<String, String> nameMapping) {
        // Sort longest name first so we ensure .so files map to their implementation
        libraries.stream()
                .sorted((File a, File b) -> -Integer.compare(a.getName().length(), b.getName().length()))
                .forEach((File f) -> getAbsoluteNames(f.getName(), f.getName(), nameMapping));
    }

    @SuppressWarnings("InfiniteRecursion")
    static void getAbsoluteNames(String absolute, String library, Map<String, String> nameMapping) {
        nameMapping.putIfAbsent(library, absolute);
        // If we are on Linux, then libXXX.so.1.0 == libXXX.so.1 == libXXX.so
        try {
            Integer.valueOf(library.substring(library.lastIndexOf('.') + 1));
            getAbsoluteNames(absolute, library.substring(0, library.lastIndexOf('.')), nameMapping);
        } catch (NumberFormatException ex) {
        }
    }

    static Stream<String> parse(String response, Pattern lineRegex, Map<String, String> searchPaths) {
        return Splitter.on('\n').splitToStream(response)
                .map(line -> lineRegex.matcher(line))
                .filter(m -> m.matches())
                .peek(m -> {
                    if (m.groupCount() > 1) {
                        String absolute = m.group(2);
                        if (absolute != null && !absolute.isEmpty() && absolute.endsWith(m.group(1))) {
                            searchPaths.put(m.group(1), absolute.substring(0, absolute.length() - m.group(1).length()));
                        }
                    }
                })
                .map(m -> m.group(1));
    }

    public static Stream<String> getDependents(File dll, Map<String, String> searchPaths) throws IOException {
        boolean isWindows = System.getProperty("os.name")
                .toLowerCase().startsWith("windows");
        List<String> commands = new ArrayList<>();
        if (isWindows) {
            commands.addAll(Arrays.asList("cmd", "/c", "objdump", "-p", dll.getAbsolutePath()));
        } else {
            commands.addAll(Arrays.asList("ldd", dll.getAbsolutePath()));
        }
        try {
            String output = new ProcessExecutor().command(commands)
                    .readOutput(true).execute()
                    .outputUTF8();
            if (isWindows) {
                return parse(output, Pattern.compile("^\\s*DLL Name:\\s*([^\\s\\.\\\\\\/]+\\.dll)\\s*$"), searchPaths);
            } else {
                return parse(output,
                        Pattern.compile("^\\s*([^\\s\\\\\\/]+) => (?>([^\\s]+)\\s\\(0x[0-9a-f]+\\)|not found)$"),
                        searchPaths);
            }
        } catch (IOException | InterruptedException | TimeoutException | InvalidExitValueException ex) {
            throw new IOException("Failed determining library dependencies", ex);
        }
    }
}
