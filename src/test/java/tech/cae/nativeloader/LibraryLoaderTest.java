package tech.cae.nativeloader;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Reading the files that drive the loader, and surviving the shapes of
 * dependency graph that turn up in real packaged libraries.
 * <p>
 * Nothing here loads a native library - that needs a platform's worth of
 * fixtures - so the graph walk is exercised through a class loader serving
 * {@code .deps} files from memory, up to the point where a real library would be
 * opened.
 */
public class LibraryLoaderTest {

    private static Path libraries;

    /**
     * Extraction is a real write to a real directory, and the one it would use
     * otherwise is the developer's own - which the loader will go on reading for
     * as long as the machine lives, so a test's leavings would outlast the test
     * run by rather a lot.
     */
    @BeforeClass
    public static void extractSomewhereDisposable() throws IOException {
        libraries = Files.createTempDirectory("nativeloader");
        System.setProperty(LibraryLoader.LIBRARIES_PROPERTY, libraries.toString());
    }

    @AfterClass
    public static void takeItAwayAgain() throws IOException {
        System.clearProperty(LibraryLoader.LIBRARIES_PROPERTY);
        try (var walk = Files.walk(libraries)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    // ---- Line endings -------------------------------------------------------

    @Test
    public void depsAreReadWhicheverLineEndingWroteThem() throws Exception {
        assertEquals(List.of("libgcc_s_seh-1.dll", "libwinpthread-1.dll"),
                deps("libgcc_s_seh-1.dll\nlibwinpthread-1.dll"));
        assertEquals("a CRLF file must not leave a carriage return on every name but the last",
                List.of("libgcc_s_seh-1.dll", "libwinpthread-1.dll"),
                deps("libgcc_s_seh-1.dll\r\nlibwinpthread-1.dll"));
        assertEquals(List.of("libgcc_s_seh-1.dll"),
                deps("libgcc_s_seh-1.dll\r\n"));
    }

    @Test
    public void blankLinesAndStrayWhitespaceAreIgnored() throws Exception {
        assertEquals(List.of("a.dll", "b.dll"), deps("a.dll\n\n  b.dll  \n"));
    }

    @Test
    public void anEmptyDepsFileIsALibraryWithNoDependenciesNotAMissingOne() throws Exception {
        // The distinction decides everything downstream: an empty list still means
        // "extract and load the packaged copy", where null means "go and find it
        // on the system"
        assertEquals(List.of(), deps(""));
        assertNotNull(deps(""));
    }

    @Test
    public void searchPathsAreReadTheSameWay() {
        Set<String> paths = LibraryLoader.getSearchPaths(
                serving(Map.of("libraries/searchpaths", "/usr/lib\r\n/usr/local/lib")), "libraries");
        assertEquals(Set.of("/usr/lib", "/usr/local/lib"), paths);
    }

    @Test
    public void noSearchPathsResourceIsAnEmptySetRatherThanNull() {
        // findInSystem reads this after java.library.path and PATH have both
        // missed, so a null here would be a NullPointerException in place of the
        // message saying the library could not be found anywhere
        assertEquals(Set.of(), LibraryLoader.getSearchPaths(serving(Map.of()), "libraries"));
    }

    // ---- Cycles -------------------------------------------------------------

    /**
     * Two libraries that import each other - freetype and harfbuzz do exactly
     * this - must not send the walk round forever. Before this was handled the
     * result was a {@link StackOverflowError}, which says nothing about which
     * libraries were involved.
     */
    @Test(timeout = 10000)
    public void aDependencyCycleTerminates() {
        ClassLoader cl = serving(Map.of(
                "libraries/libfreetype-6.dll", "",
                "libraries/libfreetype-6.dll.deps", "libharfbuzz-0.dll",
                "libraries/libharfbuzz-0.dll", "",
                "libraries/libharfbuzz-0.dll.deps", "libfreetype-6.dll"));
        try {
            LibraryLoader.load(cl, "libraries", "libfreetype-6.dll");
        } catch (NativeLoaderException | UnsatisfiedLinkError expected) {
            // There is no such library to open; reaching the attempt is the point
        }
    }

    @Test(timeout = 10000)
    public void aLongerCycleTerminatesToo() {
        ClassLoader cl = serving(Map.of(
                "libraries/a.dll", "",
                "libraries/a.dll.deps", "b.dll",
                "libraries/b.dll", "",
                "libraries/b.dll.deps", "c.dll",
                "libraries/c.dll", "",
                "libraries/c.dll.deps", "a.dll"));
        try {
            LibraryLoader.load(cl, "libraries", "a.dll");
        } catch (NativeLoaderException | UnsatisfiedLinkError expected) {
        }
    }

    /**
     * A library reached twice by two different routes is a diamond, not a cycle,
     * and has to stay loadable - it is much the commoner shape, since everything
     * in an OpenCASCADE build depends on libTKernel.
     */
    @Test(timeout = 10000)
    public void aDiamondIsNotMistakenForACycle() {
        ClassLoader cl = serving(Map.of(
                "libraries/top.dll", "",
                "libraries/top.dll.deps", "left.dll\nright.dll",
                "libraries/left.dll", "",
                "libraries/left.dll.deps", "shared.dll",
                "libraries/right.dll", "",
                "libraries/right.dll.deps", "shared.dll",
                "libraries/shared.dll", "",
                "libraries/shared.dll.deps", ""));
        try {
            LibraryLoader.load(cl, "libraries", "top.dll");
        } catch (NativeLoaderException | UnsatisfiedLinkError expected) {
        }
    }

    // ---- Platform libraries -------------------------------------------------

    /**
     * A dependency the jar does not carry belongs to the platform, and has to be
     * left for the operating system to resolve rather than loaded from here.
     * <p>
     * There is no way to ask for it by name on Linux. The {@code .deps} files
     * record a versioned filename - {@code libfreetype.so.6} - because that is
     * what {@code ldd} reported, but {@code System.loadLibrary} can only ask for
     * {@code libfreetype.so}, the development symlink a runtime image does not
     * carry, and the {@code findInSystem} fallback looks along
     * {@code java.library.path} and PATH, neither of which holds a library
     * directory on a multiarch distribution. Every one of those misses used to
     * abort the chain that asked for it, so an OpenCASCADE build failed to load on
     * a machine that had freetype installed all along.
     */
    @Test(timeout = 10000)
    public void anUnpackagedDependencyIsLeftToTheOperatingSystem() {
        ClassLoader cl = serving(Map.of(
                "libraries/libTKService.so", "",
                "libraries/libTKService.so.deps", "libfreetype.so.6"));
        try {
            LibraryLoader.load(cl, "libraries", "libTKService.so");
            fail("the packaged library is empty, so opening it should not have succeeded");
        } catch (NativeLoaderException | UnsatisfiedLinkError ex) {
            // Reaching the packaged library at all is the point: the walk has to
            // have passed over the platform one it names rather than stopped on it
            String message = String.valueOf(ex.getMessage());
            assertTrue(message, message.contains("libTKService.so"));
            assertFalse("the platform library must not be what the load failed on",
                    message.contains("freetype"));
        }
    }

    // ---- What is on disk ----------------------------------------------------

    /**
     * The failure this whole arrangement exists to prevent: a jar carrying a
     * newer library, an older one of the same name already extracted, and a
     * process that ends up running the old one.
     * <p>
     * It looked like nothing at all until something called a method that only the
     * new library has, and then it was an {@link UnsatisfiedLinkError} naming a
     * method that is present in the jar, present in the source, and present in
     * every artifact anyone thought to go and check.
     */
    @Test
    public void aLibraryThatChangedInTheJarReplacesTheOneOnDisk() throws Exception {
        ClassLoader cl = serving(Map.of("libraries/replaced.dll", "the library the jar carries"));
        File target = new File(LibraryLoader.getContainerDir(cl, "libraries/replaced.dll"), "replaced.dll");
        Files.writeString(target.toPath(), "a library left behind by an older version");

        File extracted = LibraryLoader.extract(cl, "libraries",
                new LibraryLoader.LibraryReference("replaced", "replaced.dll"));

        assertEquals(target, extracted);
        assertEquals("the library the jar carries", Files.readString(extracted.toPath()));
    }

    /**
     * And when it cannot replace it, it says so, rather than handing back the
     * library it found.
     * <p>
     * This is the shape the failure actually had. The copy is refused whenever
     * something else has the library open - which on Windows is any other process
     * that loaded it, and there is always another process - and a refusal was read
     * as "the copy on disk is the one that got loaded, so it is the right one to
     * keep". It was the right one exactly as often as the two versions happened to
     * match.
     * <p>
     * A read-only file stands in for the mapped one here, being the portable way
     * to refuse a write. What each platform then does about the replacement is its
     * own business - a POSIX rename only needs the directory - so what is asserted
     * is the part that has to hold everywhere: whatever comes back is the packaged
     * library, or nothing does.
     */
    @Test
    public void aLibraryThatCannotBeReplacedIsNotHandedBackAsIfItHadBeen() throws Exception {
        ClassLoader cl = serving(Map.of("libraries/pinned.dll", "the library the jar carries"));
        File target = new File(LibraryLoader.getContainerDir(cl, "libraries/pinned.dll"), "pinned.dll");
        Files.writeString(target.toPath(), "a library left behind by an older version");
        assertTrue(target.setReadOnly());
        try {
            File extracted = LibraryLoader.extract(cl, "libraries",
                    new LibraryLoader.LibraryReference("pinned", "pinned.dll"));
            assertEquals("a library that could not be replaced was handed back as though it had been",
                    "the library the jar carries", Files.readString(extracted.toPath()));
        } catch (NativeLoaderException expected) {
            assertTrue(String.valueOf(expected.getMessage()),
                    String.valueOf(expected.getMessage()).contains("pinned.dll"));
        } finally {
            // Or the temporary directory cannot be taken away again afterwards
            target.setWritable(true);
        }
    }

    /**
     * The other half of it: a file that is already the right one is left exactly
     * as it is, rather than rewritten.
     * <p>
     * Not an optimisation. Another process may have this library mapped, and
     * rewriting it in place is how you give that process a SIGBUS - the pages it
     * has not faulted in yet come from the file, whatever the file now says.
     */
    @Test
    public void anIdenticalLibraryIsLeftAlone() throws Exception {
        ClassLoader cl = serving(Map.of("libraries/kept.dll", "a library"));
        File target = new File(LibraryLoader.getContainerDir(cl, "libraries/kept.dll"), "kept.dll");
        Files.writeString(target.toPath(), "a library");
        // A round number of seconds, because not every filesystem records more
        long when = 1234567890000L;
        assertTrue(target.setLastModified(when));

        LibraryLoader.extract(cl, "libraries", new LibraryLoader.LibraryReference("kept", "kept.dll"));

        assertEquals("an identical library must not be rewritten", when, target.lastModified());
        assertEquals("a library", Files.readString(target.toPath()));
    }

    /**
     * Two jars that both carry a {@code libstdc++-6.dll} are not carrying the same
     * file, and one directory cannot hold both. Whichever was extracted last used
     * to win, for both of them.
     */
    @Test
    public void librariesFromDifferentJarsDoNotShareADirectory() throws Exception {
        String resource = "libraries/libstdc++-6.dll";
        String one = LibraryLoader.containerKey(
                servingFrom("jar:file:/m2/manifold-0.0.0-ce7fbb36.jar!/" + resource, resource), resource);
        String other = LibraryLoader.containerKey(
                servingFrom("jar:file:/m2/caeocc-0.0.0-467034eb.jar!/" + resource, resource), resource);

        assertNotEquals(one, other);
        assertTrue("the directory has to say which artifact and version it holds, to be any use"
                + " to whoever is reading the log line that names it", one.contains("manifold-0.0.0-ce7fbb36.jar"));
        assertEquals("the same jar has to keep the same directory, or nothing is ever reused", one,
                LibraryLoader.containerKey(
                        servingFrom("jar:file:/m2/manifold-0.0.0-ce7fbb36.jar!/" + resource, resource), resource));
    }

    // ---- Two threads at once ------------------------------------------------

    /**
     * A load in progress on another thread is not a dependency cycle.
     * <p>
     * It was indistinguishable from one, because the record of what the walk had
     * reached was shared by every thread that walked. The second thread found the
     * first thread's entry, concluded that the library was reachable from its own
     * dependencies, extracted it without loading it - which is the right answer
     * for a cycle - and returned. Nothing threw. The caller had every reason to
     * believe the library was loaded, and found out that it was not at its first
     * native call, in a stack trace with no loading in it at all.
     * <p>
     * Two threads asking for the same kernel at once is not exotic: it is what a
     * request thread and a pool of calculation workers do on any morning.
     */
    @Test(timeout = 30000)
    public void aLoadInProgressIsNotAnotherThreadsCycle() throws Exception {
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ClassLoader cl = blockingOn("libraries/slow.dll.deps", reached, release,
                Map.of("libraries/slow.dll", "", "libraries/slow.dll.deps", ""));

        Thread first = attempt(cl, new AtomicBoolean());
        assertTrue("the first thread should have reached the library it is loading",
                reached.await(10, TimeUnit.SECONDS));

        AtomicBoolean returned = new AtomicBoolean();
        Thread second = attempt(cl, returned);
        Thread.sleep(300);
        assertFalse("the second thread returned while the first was still loading, so its caller"
                + " is about to call a native method of a library nothing has loaded", returned.get());

        release.countDown();
        first.join();
        second.join();
    }

    private static Thread attempt(ClassLoader cl, AtomicBoolean returned) {
        Thread thread = new Thread(() -> {
            try {
                LibraryLoader.load(cl, "libraries", "slow.dll");
            } catch (NativeLoaderException | UnsatisfiedLinkError expected) {
                // There is no such library to open, as everywhere else here
            } finally {
                returned.set(true);
            }
        });
        thread.start();
        return thread;
    }

    // ---- Reference naming ---------------------------------------------------

    @Test
    public void aNameWithoutAnExtensionGetsThePlatformsOwn() {
        LibraryLoader.LibraryReference reference = LibraryLoader.makeReference("TKernel");
        assertEquals("TKernel", reference.getSimpleName());
        assertTrue(reference.getFileName(),
                reference.getFileName().equals("TKernel.dll")
                || reference.getFileName().equals("libTKernel.so"));
    }

    @Test
    public void aNameWithAnExtensionIsUsedAsItStands() {
        assertEquals("libTKernel.dll", LibraryLoader.makeReference("libTKernel.dll").getFileName());
    }

    // ---- Helpers ------------------------------------------------------------

    private static List<String> deps(String contents) throws NativeLoaderException {
        return LibraryLoader.getDeps(
                serving(Map.of("libraries/x.dll.deps", contents)),
                "libraries",
                new LibraryLoader.LibraryReference("x", "x.dll"));
    }

    /**
     * A class loader that serves the given resources and nothing else.
     */
    private static ClassLoader serving(Map<String, String> resources) {
        Map<String, byte[]> bytes = new HashMap<>();
        resources.forEach((name, value) -> bytes.put(name, value.getBytes(StandardCharsets.UTF_8)));
        return new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                byte[] value = bytes.get(name);
                return value == null ? null : new ByteArrayInputStream(value);
            }

            @Override
            public URL getResource(String name) {
                return null;
            }
        };
    }

    /**
     * A class loader that says the one resource it serves came from the given
     * URL, for the sake of what is made of where a library was found.
     */
    private static ClassLoader servingFrom(String url, String resource) {
        return new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                return resource.equals(name) ? new ByteArrayInputStream(new byte[0]) : null;
            }

            @Override
            public URL getResource(String name) {
                try {
                    return resource.equals(name) ? URI.create(url).toURL() : null;
                } catch (IOException ex) {
                    throw new IllegalArgumentException(url, ex);
                }
            }
        };
    }

    /**
     * A class loader that holds the first reader of one resource until it is let
     * go, so that a second thread can be caught arriving mid-load.
     */
    private static ClassLoader blockingOn(String resource, CountDownLatch reached, CountDownLatch release,
            Map<String, String> resources) {
        AtomicBoolean first = new AtomicBoolean(true);
        ClassLoader serving = serving(resources);
        return new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                if (resource.equals(name) && first.compareAndSet(true, false)) {
                    reached.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
                return serving.getResourceAsStream(name);
            }

            @Override
            public URL getResource(String name) {
                return null;
            }
        };
    }
}
