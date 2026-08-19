package tech.cae.nativeloader;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
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
                "libraries/libfreetype-6.dll.deps", "libharfbuzz-0.dll",
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
                "libraries/a.dll.deps", "b.dll",
                "libraries/b.dll.deps", "c.dll",
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
                "libraries/top.dll.deps", "left.dll\nright.dll",
                "libraries/left.dll.deps", "shared.dll",
                "libraries/right.dll.deps", "shared.dll",
                "libraries/shared.dll.deps", ""));
        try {
            LibraryLoader.load(cl, "libraries", "top.dll");
        } catch (NativeLoaderException | UnsatisfiedLinkError expected) {
        }
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
}
