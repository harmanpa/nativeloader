# nativeloader

Java library to help packaging and loading of native (.dll/.so) code

## Where libraries are extracted

`LibraryLoader` copies the libraries out of the jar before loading them, into

    <user.home>/.caetech/libraries/<jar name>-<hash of the jar's URL>/

or somewhere else entirely if `-Dtech.cae.nativeloader.libraries=<directory>` says so.

A directory per jar, rather than one shared by all of them, because the name of a
library does not identify the build of it: two artifacts that both bundle the MinGW
runtime both carry a `libstdc++-6.dll`, and one version of an artifact leaves behind
libraries that the next version will find already there. Sharing a directory meant
whichever was extracted first was the one everything then loaded.

A library already on disk is reused only if it is byte for byte the one in the jar.
Anything else is written beside it and moved into place atomically, which replaces the
directory entry rather than the file and so leaves any process that already mapped the
old one alone. A library that cannot be replaced - because another process has it open
and it is *not* the same build - is an error, not something to load anyway.

Nothing prunes the directory, so a machine keeps the libraries of every version it has
ever run. Deleting it, or any subdirectory of it, is safe while nothing is running:
the next process to want one extracts it again.
