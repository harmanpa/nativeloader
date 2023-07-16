package tech.cae.nativeloader;

import com.google.common.base.Splitter;
import com.google.common.collect.Sets;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.StandardCopyOption;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;

public class LibraryLoader {

    private static final Logger LOG = Logger.getLogger(LibraryLoader.class.getName());

    private static final Set<LibraryReference> LOADED = new HashSet<>();
    private static File LIBRARYDIR;

    public static void load(ClassLoader classLoader, String pathInJar, String... libraryNames)
            throws NativeLoaderException {
        for (String libraryName : libraryNames) {
            load(classLoader, pathInJar, makeReference(libraryName));
        }
    }

    static void load(ClassLoader classLoader, String pathInJar, LibraryReference library) throws NativeLoaderException {
        if (!LOADED.contains(library)) {
            LOG.info("Requesting load of " + library.getSimpleName() + " (" + library.getFileName() + ")");
            // Try to read a .deps file
            List<String> deps = getDeps(classLoader, pathInJar, library);
            if (deps == null) {
                // If no .deps file exists we fallback on the system
                loadSystem(library, getSearchPaths(classLoader, pathInJar));
            } else {
                // If a .deps file exists we try to load the dependencies first
                load(classLoader, pathInJar, deps.toArray(String[]::new));
                loadExtracted(classLoader, pathInJar, library);
            }
            LOADED.add(library);
        }
    }

    static void loadSystem(LibraryReference library, Set<String> searchPaths) throws NativeLoaderException {
        try {
            LOG.info("Loading " + library.getSimpleName() + " via system");
            System.loadLibrary(library.getSimpleName());
        } catch (UnsatisfiedLinkError | SecurityException ex) {
            // If we can find it ourselves we will try that
            File f = findInSystem(library, searchPaths);
            if (f != null) {
                loadAbsolute(f);
                return;
            }
            throw new NativeLoaderException("Failed to load native library " + library.getSimpleName(), ex);
        }
    }

    static void loadExtracted(ClassLoader classLoader, String pathInJar, LibraryReference library)
            throws NativeLoaderException {
        loadAbsolute(extract(classLoader, pathInJar, library));
    }

    static void loadAbsolute(File file) throws NativeLoaderException {
        try {
            LOG.info("Loading " + file.getAbsolutePath());
            System.load(file.getAbsolutePath());
        } catch (UnsatisfiedLinkError | SecurityException ex) {
            throw new NativeLoaderException("Failed to load native library " + file.getAbsolutePath(), ex);
        }
    }

    static File extract(ClassLoader classLoader, String pathInJar, LibraryReference library)
            throws NativeLoaderException {
        String resourceLocation = (pathInJar == null ? "" : (pathInJar.endsWith("/") ? pathInJar : pathInJar + "/"))
                + library.getFileName();
        try (InputStream resourceAsStream = classLoader.getResourceAsStream(resourceLocation)) {
            if (resourceAsStream == null) {
                throw new NativeLoaderException("Could not find embedded native resource " + resourceLocation);
            }
            File f = new File(getLibraryDir(), library.getFileName());
            Files.copy(resourceAsStream, f.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return f;
        } catch (IOException ex) {
            throw new NativeLoaderException("", ex);
        }
    }

    static Set<String> getSearchPaths(ClassLoader classLoader, String pathInJar) {
        String resourceLocation = (pathInJar == null ? "" : (pathInJar.endsWith("/") ? pathInJar : pathInJar + "/"))
                + "searchpaths";
        try (InputStream resourceAsStream = classLoader.getResourceAsStream(resourceLocation)) {
            if (resourceAsStream == null) {
                return null;
            }
            return Sets.newHashSet(Splitter.on('\n').omitEmptyStrings()
                    .splitToList(new String(resourceAsStream.readAllBytes(), StandardCharsets.UTF_8)));
        } catch (IOException ex) {
            return Sets.newHashSet();
        }
    }

    static List<String> getDeps(ClassLoader classLoader, String pathInJar, LibraryReference library)
            throws NativeLoaderException {
        String resourceLocation = (pathInJar == null ? "" : (pathInJar.endsWith("/") ? pathInJar : pathInJar + "/"))
                + library.getFileName() + ".deps";
        try (InputStream resourceAsStream = classLoader.getResourceAsStream(resourceLocation)) {
            if (resourceAsStream == null) {
                return null;
            }
            return Splitter.on('\n').omitEmptyStrings()
                    .splitToList(new String(resourceAsStream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new NativeLoaderException("", ex);
        }
    }

    static File getLibraryDir() throws NativeLoaderException {
        if (LIBRARYDIR == null) {
            LIBRARYDIR = new File(new File(System.getProperty("user.home") == null
                    ? System.getProperty("java.io.tmpdir")
                    : System.getProperty("user.home")), ".caetech/libraries");
            LIBRARYDIR.mkdirs();
            if (!(LIBRARYDIR.exists() && LIBRARYDIR.isDirectory())) {
                throw new NativeLoaderException("Failed to create libraries directory");
            }
        }
        if (!Files.isWritable(LIBRARYDIR.toPath())) {
            throw new NativeLoaderException("Libraries directory is not writable");
        }
        return LIBRARYDIR;
    }

    static File findInSystem(LibraryReference library, Set<String> searchPaths) {
        File f = findInSystem(library, System.getProperty("java.library.path"));
        if (f == null) {
            f = findInSystem(library, System.getenv("PATH"));
        }
        if(f == null) {
            f = findInSystem(library, searchPaths.toArray(String[]::new));
        }
        return f;
    }

    static File findInSystem(LibraryReference library, String path) {
        return path == null || path.isEmpty() ? null : findInSystem(library, path.split(File.pathSeparator));
    }

    static File findInSystem(LibraryReference library, String[] path) {
        for (String subpath : path) {
            if (!subpath.isEmpty()) {
                File f = new File(new File(subpath), library.getFileName());
                if (f.exists()) {
                    return f;
                }
            }
        }
        return null;
    }

    static boolean isWindows() {
        return System.getProperty("os.name")
                .toLowerCase().startsWith("windows");
    }

    static LibraryReference makeReference(String libraryName) {
        String shortName = libraryName.indexOf('.') > -1 ? libraryName.substring(0, libraryName.indexOf('.'))
                : libraryName;
        if (isWindows()) {
            String fileName = libraryName.indexOf('.') > -1 ? libraryName : libraryName + ".dll";
            return new LibraryReference(shortName, fileName);
        } else {
            if (shortName.startsWith("lib")) {
                shortName = shortName.substring(3);
            }
            String fileName = libraryName.indexOf('.') > -1 ? libraryName : "lib" + libraryName + ".so";
            return new LibraryReference(shortName, fileName);
        }
    }

    static class LibraryReference {

        private final String simpleName;
        private final String fileName;

        LibraryReference(String simpleName, String fileName) {
            this.simpleName = simpleName;
            this.fileName = fileName;
        }

        public String getSimpleName() {
            return simpleName;
        }

        public String getFileName() {
            return fileName;
        }

        @Override
        public int hashCode() {
            int hash = 3;
            hash = 97 * hash + Objects.hashCode(this.simpleName);
            hash = 97 * hash + Objects.hashCode(this.fileName);
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final LibraryReference other = (LibraryReference) obj;
            if (!Objects.equals(this.simpleName, other.simpleName)) {
                return false;
            }
            return Objects.equals(this.fileName, other.fileName);
        }

        @Override
        public String toString() {
            return "LibraryReference{" + "simpleName=" + simpleName + ", fileName=" + fileName + '}';
        }

    }
}
