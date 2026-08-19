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

    /**
     * Libraries taken to belong to the platform rather than to the project being
     * packaged, and so left out of the {@code .deps} files.
     * <p>
     * The C and C++ runtimes are in here because they are normally the operating
     * system's to provide - but a project built with MinGW on Windows cannot rely
     * on that, since the machine running it need not have MSYS2 installed at all,
     * so such a project ships {@code libstdc++-6.dll}, {@code libgcc_s_seh-1.dll}
     * and {@code libwinpthread-1.dll} beside its own libraries. This list must not
     * suppress those: see {@link #writeDependencies(File, List)}, where anything
     * actually present in the directory being packaged overrides the exclusion.
     */
    public static final List<String> PLATFORM_LIBRARIES = Arrays.asList(
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
    );

    public static void main(String[] args) {
        for (String arg : args) {
            try {
                writeDependencies(new File(arg));
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
        writeDependencies(directory, PLATFORM_LIBRARIES);
    }

    /**
     * Writes a {@code .deps} file beside every shared library in a directory,
     * naming what that library imports.
     * <p>
     * A name in {@code excluded} is left out - unless the directory contains it,
     * in which case it is kept whatever the exclusions say. Excluding a library
     * means "the platform will provide this", and a library sitting in the
     * directory is the project saying the opposite: it went to the trouble of
     * packaging its own copy precisely because the platform's cannot be relied on.
     * Leaving it out of the chain anyway is not a small loss - nothing names it,
     * so {@link LibraryLoader} never extracts or loads it, the packaged copy is
     * dead weight, and every library that imports it is resolved by the operating
     * system off PATH instead. That is the failure the packaging was meant to
     * prevent, and it is invisible until it lands on a machine where PATH has
     * nothing, or the wrong thing, to offer.
     *
     * @param directory the packaged libraries, normally {@code target/classes/libraries}
     * @param excluded  names taken to be the platform's, as in {@link #PLATFORM_LIBRARIES}
     */
    public static void writeDependencies(File directory, List<String> excluded) throws IOException {
        Set<File> sharedLibraries = getSharedLibraries(directory);
        Map<String, String> absoluteNames = new HashMap<>();
        getAbsoluteNames(sharedLibraries, absoluteNames);
        Set<String> allSearchPaths = new LinkedHashSet<>();
        for (File sharedLibrary : sharedLibraries) {
            // Find the dependencies of a library
            Map<String, String> searchPaths = new HashMap<>();
            List<String> depLibraries = getDependents(sharedLibrary, searchPaths)
                    .filter(name -> isPackaged(name, absoluteNames) || !nameMatches(name, excluded))
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

    /**
     * Whether the directory being packaged contains this library itself.
     * <p>
     * {@code absoluteNames} is built from the directory's own contents and keyed
     * by every name a dependency might be recorded under - the file's own name,
     * and for a Linux SONAME each shorter form of it - so containing the key is
     * the same question as the file being there.
     */
    static boolean isPackaged(String name, Map<String, String> absoluteNames) {
        return absoluteNames.containsKey(name);
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
                // Dots are allowed in the name, only separators are not. Excluding
                // them - as this did - silently drops every library whose version is
                // part of its filename: libglib-2.0-0.dll, libicuuc-70.1.dll. Silently,
                // because a name missing from a .deps file is indistinguishable from a
                // library that genuinely has no such dependency, right up until the
                // load fails with "Can't find dependent libraries" and names the
                // importer rather than the library that was dropped.
                return parse(output, Pattern.compile("^\\s*DLL Name:\\s*([^\\s\\\\\\/]+\\.dll)\\s*$",
                        Pattern.CASE_INSENSITIVE), searchPaths);
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
