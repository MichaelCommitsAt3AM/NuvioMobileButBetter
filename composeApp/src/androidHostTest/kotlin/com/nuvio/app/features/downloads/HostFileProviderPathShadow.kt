package com.nuvio.app.features.downloads

import java.io.File
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** FileProvider assumes '/' separators; use host paths for its canonical descendant check. */
@Implements(
    className = "androidx.core.content.FileProvider\$SimplePathStrategy",
    isInAndroidSdk = false,
)
class HostFileProviderPathShadow {
    @Implementation
    fun belongsToRoot(filePath: String, rootPath: String): Boolean {
        val file = File(filePath).toPath()
        val root = File(rootPath).toPath()
        return file != root && file.startsWith(root)
    }
}
