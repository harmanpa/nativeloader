package tech.cae.nativeloader;

import com.google.common.base.Splitter;
import com.google.common.collect.Sets;
import com.sun.jna.Native;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public class LibraryLoader {

    private static final Logger LOG = Logger.getLogger(LibraryLoader.class.getName());

    private static final Set<LibraryReference> LOADED = new HashSet<>();

    /**
     * The libraries whose dependencies are currently being walked, in the order
     * the walk reached them. A library is in here from the moment its
     * dependencies start loading until it has itself been loaded, which is what
     * lets {@link #load(ClassLoader, String, LibraryReference)} recognise a
     * dependency that leads back into the chain that asked for it.
     */
    private static final Set<LibraryReference> LOADING = new LinkedHashSet<>();

    private static File LIBRARYDIR;

    public static void load(ClassLoader classLoader, String pathInJar, String... libraryNames)
            throws NativeLoaderException {
        for (String libraryName : libraryNames) {
            load(classLoader, pathInJar, makeReference(libraryName));
        }
    }

    static void load(ClassLoader classLoader, String pathInJar, LibraryReference library) throws NativeLoaderException {
        if (LOADED.contains(library)) {
            return;
        }
        if (!LOADING.add(library)) {
            // This library is reachable from its own dependencies. Two shared
            // libraries that import each other are ordinary - freetype and harfbuzz
            // do, each using the other - and the operating system copes with it,
            // because it maps a library before resolving its imports and so finds
            // the partly-loaded partner already in the process.
            //
            // It cannot be loaded from here: the call further up the chain is the
            // one that will load it, and recursing into it again would not
            // terminate. What it does need is to exist, because the partner that is
            // about to load imports it and the operating system will have to find
            // it on disk rather than in the process. Extracting without loading is
            // exactly that, and it is why the search path set up in makeSearchable
            // has something to find.
            LOG.fine(() -> "Reached " + library.getFileName() + " through its own dependencies ("
                    + describeChain(library) + "), so it is extracted for the operating system"
                    + " to resolve rather than pre-loaded");
            extractOnly(classLoader, pathInJar, library, new HashSet<>());
            return;
        }
        try {
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
        } finally {
            LOADING.remove(library);
        }
    }

    /**
     * Puts a library and everything it depends on into the extraction directory,
     * without loading any of it.
     * <p>
     * For the library on the far side of a cycle, which cannot be loaded here.
     * The operating system is going to have to resolve it - and then resolve what
     * <em>it</em> imports, and so on - out of the directory
     * {@link #makeSearchable} put on the search path, so the whole closure has to
     * be on disk, not just the one library. Stopping at the library itself gets
     * as far as freetype and then fails on the png and zlib underneath it.
     * <p>
     * A name that is not packaged is left alone: a cycle can run through a system
     * library, which the operating system finds for itself.
     */
    private static void extractOnly(ClassLoader classLoader, String pathInJar, LibraryReference library,
            Set<LibraryReference> extracted) {
        if (LOADED.contains(library) || !extracted.add(library)) {
            return;
        }
        try {
            extract(classLoader, pathInJar, library);
        } catch (NativeLoaderException ex) {
            LOG.log(Level.FINE, ex, () -> library.getFileName()
                    + " is not packaged here, so it is the operating system's to find");
            return;
        }
        try {
            List<String> deps = getDeps(classLoader, pathInJar, library);
            if (deps != null) {
                for (String dep : deps) {
                    extractOnly(classLoader, pathInJar, makeReference(dep), extracted);
                }
            }
        } catch (NativeLoaderException ex) {
            LOG.log(Level.FINE, ex, () -> "Could not read the dependencies of " + library.getFileName());
        }
    }

    /**
     * The chain of libraries currently being loaded, from the one that was
     * reached a second time onwards, for reporting a cycle.
     */
    private static String describeChain(LibraryReference from) {
        StringBuilder chain = new StringBuilder();
        boolean started = false;
        for (LibraryReference reference : LOADING) {
            started |= reference.equals(from);
            if (started) {
                chain.append(reference.getFileName()).append(" -> ");
            }
        }
        return chain.append(from.getFileName()).toString();
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
            try {
                Files.copy(resourceAsStream, f.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                if (!f.isFile()) {
                    throw ex;
                }
                // Windows will not let a mapped library be rewritten, so this is a
                // library already loaded into the process - which happens as soon as
                // the operating system resolves one itself, out of the directory
                // makeSearchable put on the search path, rather than being told to by
                // the walk below. The copy on disk is the one that got loaded, so it
                // is both unreplaceable and the right one to keep.
                LOG.fine(() -> f.getAbsolutePath() + " is already in use, so the extracted copy is left as it is");
            }
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
                // Empty rather than null: the one caller passes this straight to
                // findInSystem, which reads it after java.library.path and PATH have
                // both missed - so null here turns "the library is nowhere" into a
                // NullPointerException at the exact moment the real message is wanted
                return Sets.newHashSet();
            }
            return Sets.newHashSet(LINES
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
            return LINES
                    .splitToList(new String(resourceAsStream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new NativeLoaderException("", ex);
        }
    }

    /**
     * Splits the line-per-entry files this class reads.
     * <p>
     * Trimming is what makes the line ending irrelevant. These files are written
     * with LF, but they are also written by hand and by build scripts, and
     * anything that writes them on Windows - PowerShell's {@code Set-Content} and
     * {@code Out-File}, Ant's {@code echo} from a CRLF checkout - produces CRLF.
     * Splitting on LF alone then leaves a carriage return on the end of every
     * name but the last, so a lookup for {@code libTKBin.dll\r} finds no resource
     * and the library silently drops to a system search instead of loading the
     * copy that was packaged for it. Silent because the name is only wrong by an
     * invisible character, and the fallback usually finds something.
     */
    private static final Splitter LINES = Splitter.on('\n').trimResults().omitEmptyStrings();

    static File getLibraryDir() throws NativeLoaderException {
        if (LIBRARYDIR == null) {
            LIBRARYDIR = new File(new File(System.getProperty("user.home") == null
                    ? System.getProperty("java.io.tmpdir")
                    : System.getProperty("user.home")), ".caetech/libraries");
            LIBRARYDIR.mkdirs();
            if (!(LIBRARYDIR.exists() && LIBRARYDIR.isDirectory())) {
                throw new NativeLoaderException("Failed to create libraries directory");
            }
            makeSearchable(LIBRARYDIR);
        }
        if (!Files.isWritable(LIBRARYDIR.toPath())) {
            throw new NativeLoaderException("Libraries directory is not writable");
        }
        return LIBRARYDIR;
    }

    /**
     * Asks Windows to look in the extraction directory when it resolves one
     * extracted library's dependencies on another.
     * <p>
     * Loading a library by absolute path does <em>not</em> put its directory on
     * the search path for its own imports - the standard order starts at the
     * executable's directory, and the extraction directory is nowhere in it. The
     * {@code .deps} walk normally hides that, by loading every dependency before
     * whatever imports it, so the operating system finds each one already in the
     * process and never has to search at all.
     * <p>
     * What defeats the walk is a cycle. Two libraries that import each other -
     * freetype and harfbuzz do - cannot both be loaded first, so whichever goes
     * in second has an import the operating system has to find for itself, and it
     * fails with "Can't find dependent libraries". Making the directory
     * searchable is the only thing that answers that, and it also turns the whole
     * {@code .deps} mechanism from load-bearing into an optimisation.
     * <p>
     * {@code SetDllDirectory} rather than {@code AddDllDirectory}, despite the
     * latter being the newer call: {@code AddDllDirectory} only affects loads
     * that pass the {@code LOAD_LIBRARY_SEARCH_*} flags, and the flags the JVM
     * passes are not ours to choose. Making it apply would mean
     * {@code SetDefaultDllDirectories}, which switches the whole process to the
     * new scheme and drops PATH out of the search order for every library in the
     * JVM, ours or not. {@code SetDllDirectory} inserts one directory into the
     * order that a plain {@code LoadLibrary} already uses and leaves PATH alone.
     * It takes a single directory, which is exactly what there is to give it.
     * <p>
     * Failure is not fatal. Everything that worked before this call existed still
     * works without it; only a cycle needs it.
     */
    private static void makeSearchable(File directory) {
        if (!isWindows()) {
            // The equivalent on Linux is the RPATH the library was linked with, or
            // LD_LIBRARY_PATH, and neither can be changed once the process is running
            return;
        }
        try {
            if (Kernel32.INSTANCE.SetDllDirectoryW(new WString(directory.getAbsolutePath()))) {
                LOG.fine(() -> "Windows will search " + directory.getAbsolutePath()
                        + " when resolving library dependencies");
            } else {
                LOG.warning("Windows refused to search " + directory.getAbsolutePath()
                        + "; libraries that import each other will not load");
            }
        } catch (Throwable ex) {
            // Kernel32 is always there, so this is JNA missing or unable to unpack
            // itself. Worth a warning: what stops working is not obvious from the
            // error that follows it
            LOG.log(Level.WARNING, ex, () -> "Could not add " + directory.getAbsolutePath()
                    + " to the library search path, so libraries that import each other will not load");
        }
    }

    private interface Kernel32 extends StdCallLibrary {

        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);

        /**
         * The wide-character entry point by name, so that no character set mapping
         * has to be configured for it.
         */
        boolean SetDllDirectoryW(WString lpPathName);
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
