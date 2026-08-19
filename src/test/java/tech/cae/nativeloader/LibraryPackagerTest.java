package tech.cae.nativeloader;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The name matching that decides what reaches a {@code .deps} file.
 */
public class LibraryPackagerTest {

    private static final List<String> EXCLUDED = LibraryPackager.PLATFORM_LIBRARIES;

    // ---- What counts as a platform library ---------------------------------

    @Test
    public void theCppRuntimeIsAPlatformLibraryByName() {
        assertTrue(LibraryPackager.nameMatches("libstdc++-6.dll", EXCLUDED));
        assertTrue(LibraryPackager.nameMatches("libgcc_s_seh-1.dll", EXCLUDED));
        assertTrue(LibraryPackager.nameMatches("libwinpthread-1.dll", EXCLUDED));
        assertTrue(LibraryPackager.nameMatches("libstdc++.so.6", EXCLUDED));
    }

    @Test
    public void theWindowsApiSetsAreMatchedByWildcard() {
        assertTrue(LibraryPackager.nameMatches("api-ms-win-crt-runtime-l1-1-0.dll", EXCLUDED));
        assertTrue(LibraryPackager.nameMatches("KERNEL32.dll", EXCLUDED));
    }

    @Test
    public void aProjectsOwnLibrariesAreNot() {
        assertFalse(LibraryPackager.nameMatches("libTKernel.dll", EXCLUDED));
        assertFalse(LibraryPackager.nameMatches("libjmanifold.dll", EXCLUDED));
        assertFalse(LibraryPackager.nameMatches("libfreetype-6.dll", EXCLUDED));
        // Close enough to "stdc++" to be worth pinning: shortName strips the lib
        // prefix and the trailing -N, and must not then match on a prefix
        assertFalse(LibraryPackager.nameMatches("libstdcxxhelper-1.dll", EXCLUDED));
    }

    // ---- Packaging a copy overrides the exclusion ---------------------------

    /**
     * The whole point of the override: a project that ships its own C++ runtime
     * has to have it named in the chain, or {@link LibraryLoader} never loads the
     * copy it shipped.
     */
    @Test
    public void aPackagedPlatformLibraryIsNotExcluded() {
        Map<String, String> packaged = packaged("libjmanifold.dll", "libstdc++-6.dll",
                "libgcc_s_seh-1.dll", "libwinpthread-1.dll");
        assertTrue("the bundled C++ runtime is kept",
                LibraryPackager.isPackaged("libstdc++-6.dll", packaged));
        assertFalse("but a system library that is not bundled is still dropped",
                LibraryPackager.isPackaged("KERNEL32.dll", packaged));
    }

    @Test
    public void aPlatformLibraryThatIsNotPackagedStaysExcluded() {
        Map<String, String> packaged = packaged("libjmanifold.dll");
        assertFalse(LibraryPackager.isPackaged("libstdc++-6.dll", packaged));
    }

    /**
     * A Linux dependency is recorded under whichever form of the SONAME the
     * linker wrote, so the check has to see through all of them.
     */
    @Test
    public void aSonameIsRecognisedInEveryFormItMayBeNamedBy() {
        Map<String, String> packaged = packaged("libTKernel.so.7.7.0");
        assertTrue(LibraryPackager.isPackaged("libTKernel.so.7.7.0", packaged));
        assertTrue(LibraryPackager.isPackaged("libTKernel.so.7.7", packaged));
        assertTrue(LibraryPackager.isPackaged("libTKernel.so.7", packaged));
        assertTrue(LibraryPackager.isPackaged("libTKernel.so", packaged));
        assertEquals("and all of them resolve to the real file",
                "libTKernel.so.7.7.0", packaged.get("libTKernel.so"));
    }

    // ---- Short names --------------------------------------------------------

    @Test
    public void shortNameStripsThePrefixExtensionAndSoversion() {
        assertEquals("stdc++", LibraryPackager.shortName("libstdc++-6.dll"));
        assertEquals("gcc_s_seh", LibraryPackager.shortName("libgcc_s_seh-1.dll"));
        assertEquals("TKernel", LibraryPackager.shortName("libTKernel.so.7.7.0"));
    }

    @Test
    public void aTrailingNumberIsOnlyASoversionWhenItIsAllDigits() {
        assertEquals("brotli", LibraryPackager.removeVersionNumber("brotli-1"));
        assertEquals("some-name", LibraryPackager.removeVersionNumber("some-name"));
        assertEquals("mixed-1a", LibraryPackager.removeVersionNumber("mixed-1a"));
    }

    // ---- Reading objdump ----------------------------------------------------

    /**
     * A version in the filename is ordinary on Windows, and a name that does not
     * match here never reaches a {@code .deps} file at all.
     */
    @Test
    public void aDllNameMayContainDots() {
        Map<String, String> searchPaths = new HashMap<>();
        List<String> names = LibraryPackager.parse(String.join("\n",
                "\tDLL Name: KERNEL32.dll",
                "\tDLL Name: libglib-2.0-0.dll",
                "\tDLL Name: libstdc++-6.dll",
                "\tDLL Name: libicuuc-70.1.dll",
                "\tnot a dll name line"),
                WINDOWS_DLL_NAME, searchPaths).collect(java.util.stream.Collectors.toList());
        assertEquals(List.of("KERNEL32.dll", "libglib-2.0-0.dll", "libstdc++-6.dll", "libicuuc-70.1.dll"), names);
    }

    /**
     * The pattern {@link LibraryPackager#getDependents} uses on objdump output,
     * repeated here because it is built inside that method.
     */
    private static final java.util.regex.Pattern WINDOWS_DLL_NAME = java.util.regex.Pattern.compile(
            "^\\s*DLL Name:\\s*([^\\s\\\\\\/]+\\.dll)\\s*$", java.util.regex.Pattern.CASE_INSENSITIVE);

    private static Map<String, String> packaged(String... fileNames) {
        Map<String, String> names = new HashMap<>();
        for (String fileName : fileNames) {
            LibraryPackager.getAbsoluteNames(fileName, fileName, names);
        }
        assertFalse(Arrays.asList(fileNames).isEmpty());
        return names;
    }
}
