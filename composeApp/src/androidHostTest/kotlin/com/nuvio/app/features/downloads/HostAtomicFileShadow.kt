package com.nuvio.app.features.downloads

import android.util.AtomicFile
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Keep AtomicFile's real write, sync and recovery paths, but give its rename Android's
 * replace-existing semantics. File.renameTo cannot replace a file on Windows, so the
 * unmodified framework implementation otherwise silently loses every subsequent write.
 */
@Implements(AtomicFile::class, minSdk = 30)
class HostAtomicFileShadow {
    companion object {
        @JvmStatic
        @Implementation
        fun rename(source: File, target: File) {
            Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }
    }
}
