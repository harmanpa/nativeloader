package tech.cae.nativeloader;

import com.google.common.base.Splitter;
import com.google.common.collect.Sets;
import com.sun.jna.Native;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public class LibraryLoader {

    private static final Logger LOG = Logger.getLogger(LibraryLoader.class.getName());

    /**
     * Held for the whole of a load, dependency walk and extraction included.
     * <p>
     * Everything mutable in this class is guarded by it, but the reason for it is
     * not really the collections: it is that {@link #LOADING} means "this walk has
     * reached that library", and a walk is a single call chain. Two threads
     * loading at once - a request thread and a worker thread each asking for the
     * same geometry kernel is the ordinary case - shared one set of it, so the
     * second thread found the first thread's entry, took it for a dependency cycle
     * and returned <em>having extracted but not loaded anything</em>. Its call to
     * {@code load} then looked successful, and the very next native call died with
     * an {@link UnsatisfiedLinkError} naming a method that was in the jar all
     * along. Serialising is enough to make that impossible, and loading a native
     * library is a once-per-process cost that nothing waits on twice.
     */
    private static final Object LOCK = new Object();

    private static final Set<LibraryReference> LOADED = new HashSet<>();

    /**
     * The libraries whose dependencies are currently being walked, in the order
     * the walk reached them. A library is in here from the moment its
     * dependencies start loading until it has itself been loaded, which is what
     * lets {@link #load(ClassLoader, String, LibraryReference, boolean)}
     * recognise a dependency that leads back into the chain that asked for it.
     * <p>
     * Only ever the one walk in here at a time - see {@link #LOCK}.
     */
    private static final Set<LibraryReference> LOADING = new LinkedHashSet<>();

    /**
     * The directory {@link #makeSearchable} last handed to Windows, so that
     * loading a run of libraries out of one container does not repeat the call.
     */
    private static File SEARCHABLE;

    public static void load(ClassLoader classLoader, String pathInJar, String... libraryNames)
            throws NativeLoaderException {
        synchronized (LOCK) {
            for (String libraryName : libraryNames) {
                load(classLoader, pathInJar, makeReference(libraryName), false);
            }
        }
    }

    /**
     * Loads one library and, first, everything it depends on. Call it holding
     * {@link #LOCK}: it reads and writes the state that says what has been loaded
     * and what this walk has already reached.
     *
     * @param isDependency whether this library was reached through another
     *                     library's {@code .deps} file rather than asked for by
     *                     name, which is what decides whether an unpackaged
     *                     library is an error or the operating system's to find
     */
    static void load(ClassLoader classLoader, String pathInJar, LibraryReference library, boolean isDependency)
            throws NativeLoaderException {
        if (LOADED.contains(library)) {
            return;
        }
        if (isDependency && !isPackaged(classLoader, pathInJar, library)) {
            // A dependency that is not in the jar belongs to the platform, and the
            // operating system will resolve it when the library that imports it is
            // loaded. Trying to pre-load it here cannot work on Linux and does not
            // need to on Windows.
            //
            // It cannot work on Linux because there is no way to ask for a versioned
            // library by name: System.loadLibrary("freetype") looks for
            // libfreetype.so, the development symlink, which a runtime image does not
            // carry - only libfreetype.so.6. The findInSystem fallback then looks for
            // that exact filename along java.library.path and PATH, and neither
            // contains a library directory on a multiarch distribution, where it
            // lives in /usr/lib/x86_64-linux-gnu. So the load failed, and took the
            // whole chain that asked for it down with it, on a machine that had the
            // library installed all along.
            //
            // Leaving it alone is also what makes the result correct rather than
            // merely working: ld.so resolves it out of the shared library cache,
            // which is the distribution's own answer to where its libraries are, and
            // no build machine's paths are baked in. If it is genuinely absent, the
            // System.load of the library that imports it fails and names it.
            LOG.fine(() -> library.getFileName() + " is not packaged here, so it is left"
                    + " for the operating system to resolve");
            LOADED.add(library);
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
                for (String dep : deps) {
                    load(classLoader, pathInJar, makeReference(dep), true);
                }
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
            Set<LibraryReference> extracted) throws NativeLoaderException {
        if (LOADED.contains(library) || !extracted.add(library)) {
            return;
        }
        if (!isPackaged(classLoader, pathInJar, library)) {
            LOG.fine(() -> library.getFileName()
                    + " is not packaged here, so it is the operating system's to find");
            return;
        }
        // Asked for and present, so a failure to put it on disk is a failure -
        // the library that imports it is about to ask the operating system to
        // find it there, and will fail with nothing but "can't find dependent
        // libraries" to say why
        extract(classLoader, pathInJar, library);
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
        File file = extract(classLoader, pathInJar, library);
        // Before the load rather than once at startup, because there is a
        // directory per container now and only one of them can be the searchable
        // one. The library about to be loaded is the one whose imports Windows
        // may have to resolve, so its own directory is the right answer
        makeSearchable(file.getParentFile());
        loadAbsolute(file);
    }

    static void loadAbsolute(File file) throws NativeLoaderException {
        try {
            LOG.info("Loading " + file.getAbsolutePath());
            System.load(file.getAbsolutePath());
        } catch (UnsatisfiedLinkError | SecurityException ex) {
            throw new NativeLoaderException("Failed to load native library " + file.getAbsolutePath(), ex);
        }
    }

    /**
     * Whether this library is one of the ones packaged in the jar, as opposed to
     * one the platform is expected to provide.
     */
    static boolean isPackaged(ClassLoader classLoader, String pathInJar, LibraryReference library) {
        try (InputStream resourceAsStream = classLoader
                .getResourceAsStream(resourceLocation(pathInJar, library.getFileName()))) {
            return resourceAsStream != null;
        } catch (IOException ex) {
            return false;
        }
    }

    static String resourceLocation(String pathInJar, String name) {
        return (pathInJar == null ? "" : (pathInJar.endsWith("/") ? pathInJar : pathInJar + "/")) + name;
    }

    /**
     * Puts one packaged library on disk and says where it went.
     * <p>
     * What it will not do is hand back a file whose contents are not the ones in
     * the jar. That used to be possible, and was the worst kind of failure this
     * class can produce: the copy is refused whenever another process has the
     * library mapped, the refusal was taken to mean "the loaded copy is the right
     * one to keep", and so a jar carrying a newer library quietly linked against
     * an older one left behind by a previous version. Java sees the new class with
     * its new native method, the process has the old library without it, and the
     * error names a method that is present in every artifact you can go and look
     * at.
     * <p>
     * So an identical file is reused untouched - which is both the common case and
     * the one that must not rewrite a mapped file - and anything else is written
     * beside it and moved into place atomically. The move replaces the directory
     * entry rather than the file, so a process that already mapped the old one
     * keeps reading the old inode instead of having the ground moved under it,
     * which on Linux is the difference between an upgrade and a SIGBUS in an
     * unrelated JVM. If even that cannot be done, it is an exception: a wrong
     * library is not a working one.
     */
    static File extract(ClassLoader classLoader, String pathInJar, LibraryReference library)
            throws NativeLoaderException {
        String resourceLocation = resourceLocation(pathInJar, library.getFileName());
        if (!isPackaged(classLoader, pathInJar, library)) {
            throw new NativeLoaderException("Could not find embedded native resource " + resourceLocation);
        }
        File f = new File(getContainerDir(classLoader, resourceLocation), library.getFileName());
        try {
            if (isAlreadyExtracted(classLoader, resourceLocation, f)) {
                LOG.fine(() -> f.getAbsolutePath() + " is already the packaged copy, so it is left as it is");
                return f;
            }
            replace(classLoader, resourceLocation, f);
            return f;
        } catch (IOException ex) {
            throw new NativeLoaderException("Could not extract " + resourceLocation
                    + " to " + f.getAbsolutePath(), ex);
        }
    }

    /**
     * Whether the file already on disk is byte for byte the one in the jar.
     * <p>
     * Byte for byte rather than by size or timestamp: the versions of a native
     * library that differ only in what was added to it are exactly the ones this
     * has to tell apart, and a copy carries no version to compare.
     */
    private static boolean isAlreadyExtracted(ClassLoader classLoader, String resourceLocation, File f)
            throws IOException {
        if (!f.isFile()) {
            return false;
        }
        try (InputStream extracted = new BufferedInputStream(Files.newInputStream(f.toPath()));
                InputStream packaged = classLoader.getResourceAsStream(resourceLocation)) {
            if (packaged == null) {
                return false;
            }
            byte[] a = new byte[1 << 16];
            byte[] b = new byte[1 << 16];
            for (;;) {
                int read = extracted.readNBytes(a, 0, a.length);
                if (read != packaged.readNBytes(b, 0, b.length)) {
                    return false;
                }
                if (!Arrays.equals(a, 0, read, b, 0, read)) {
                    return false;
                }
                if (read == 0) {
                    return true;
                }
            }
        }
    }

    private static void replace(ClassLoader classLoader, String resourceLocation, File f)
            throws IOException, NativeLoaderException {
        Path temporary = Files.createTempFile(f.getParentFile().toPath(), f.getName() + ".", ".extracting");
        try {
            try (InputStream resourceAsStream = classLoader.getResourceAsStream(resourceLocation)) {
                if (resourceAsStream == null) {
                    throw new NativeLoaderException("Could not find embedded native resource " + resourceLocation);
                }
                Files.copy(resourceAsStream, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(temporary, f.toPath(), StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ex) {
                // Windows will not replace a mapped library at all, and this is the
                // only case where that matters: the file that is in the way holds
                // something other than what the jar carries. Another process getting
                // there first is not that - it wrote the same bytes - so look before
                // giving up
                if (isAlreadyExtracted(classLoader, resourceLocation, f)) {
                    return;
                }
                throw new IOException(f.getAbsolutePath() + " holds a different build of this library"
                        + " and cannot be replaced, which usually means another process has it loaded."
                        + " Stop the processes using it, or delete it, and start again", ex);
            }
        } finally {
            Files.deleteIfExists(temporary);
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

    static final String LIBRARIES_PROPERTY = "tech.cae.nativeloader.libraries";

    /**
     * Where everything this class extracts goes, overridable with
     * {@code -Dtech.cae.nativeloader.libraries=...} for a deployment that would
     * rather keep it somewhere it can see and clear out.
     */
    static File getLibraryRoot() throws NativeLoaderException {
        String configured = System.getProperty(LIBRARIES_PROPERTY);
        File root = configured != null
                ? new File(configured)
                : new File(new File(System.getProperty("user.home") == null
                        ? System.getProperty("java.io.tmpdir")
                        : System.getProperty("user.home")), ".caetech/libraries");
        root.mkdirs();
        if (!(root.exists() && root.isDirectory())) {
            throw new NativeLoaderException("Failed to create libraries directory " + root.getAbsolutePath());
        }
        return root;
    }

    /**
     * The directory the libraries of one container - one jar, in practice - are
     * extracted into.
     * <p>
     * A directory each, where there used to be a single shared one, because a
     * library's filename does not identify the build of it. Two artifacts that
     * both bundle the MinGW runtime each carry a {@code libstdc++-6.dll}, and if
     * they were built against different C runtimes only one of those two files is
     * the right one for either of them - but they have the same name, so in one
     * directory whichever was extracted last was the one both of them then loaded.
     * The same collision is what let an old version of an artifact leave a library
     * behind for a new version of it to pick up. Keyed on the container, a name
     * only has to be unique within the jar that chose it, which it is.
     * <p>
     * The key keeps the container's filename in it as well as a hash of its whole
     * URL, so that anyone looking at the directory - or at the path in the log
     * line that says what was loaded - can see which artifact and which version
     * they are looking at.
     */
    static File getContainerDir(ClassLoader classLoader, String resourceLocation) throws NativeLoaderException {
        File dir = new File(getLibraryRoot(), containerKey(classLoader, resourceLocation));
        dir.mkdirs();
        if (!(dir.exists() && dir.isDirectory())) {
            throw new NativeLoaderException("Failed to create libraries directory " + dir.getAbsolutePath());
        }
        if (!Files.isWritable(dir.toPath())) {
            throw new NativeLoaderException("Libraries directory " + dir.getAbsolutePath() + " is not writable");
        }
        return dir;
    }

    /**
     * A directory name for whatever holds a resource: the jar, or the directory
     * of classes, that the class loader found it in.
     */
    static String containerKey(ClassLoader classLoader, String resourceLocation) {
        URL url = classLoader.getResource(resourceLocation);
        if (url == null) {
            // A class loader is entitled to serve a stream without ever admitting
            // where it came from. Nothing then distinguishes one container from
            // another, so they share, exactly as everything used to
            return "shared";
        }
        String container = url.toString();
        int embedded = container.indexOf("!/");
        container = embedded < 0
                // Not in an archive: the resource is a file, and what holds it is
                // the directory tree above it, which is what is left after the
                // resource's own path is taken off the end
                ? container.substring(0, Math.max(0, container.length() - resourceLocation.length()))
                : container.substring(0, embedded);
        return name(container) + "-" + shortHash(container);
    }

    /**
     * The last path segment of a URL, reduced to the characters that mean the
     * same thing to every filesystem.
     */
    private static String name(String container) {
        String name = container.substring(container.lastIndexOf('/') + 1)
                .replaceAll("[^A-Za-z0-9._-]", "_");
        return name.isEmpty() ? "libraries" : name;
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hash = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                hash.append(Character.forDigit((digest[i] >> 4) & 0xf, 16))
                        .append(Character.forDigit(digest[i] & 0xf, 16));
            }
            return hash.toString();
        } catch (NoSuchAlgorithmException ex) {
            // Every Java platform is required to implement SHA-256
            throw new IllegalStateException(ex);
        }
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
     * <p>
     * It takes one directory and there is now one per container, so this is called
     * before each load rather than once: the directory that has to be searchable
     * is the one holding the library whose imports are about to be resolved.
     */
    private static void makeSearchable(File directory) {
        if (!isWindows()) {
            // The equivalent on Linux is the RPATH the library was linked with, or
            // LD_LIBRARY_PATH, and neither can be changed once the process is running
            return;
        }
        if (directory == null || directory.equals(SEARCHABLE)) {
            return;
        }
        SEARCHABLE = directory;
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
