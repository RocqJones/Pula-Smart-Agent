package com.jonesmb.pulasmartagent.platform.media

/**
 * Compresses a photo at [sourcePath] and writes the result to [destPath].
 *
 * Returns true if compression succeeded. The caller is responsible for
 * deleting [destPath] on failure and [sourcePath] after a successful upload.
 *
 * [quality] is a 0–100 hint (JPEG scale); [maxDimension] caps width/height in pixels.
 * Both values are driven by [com.jonesmb.pulasmartagent.core.constants.CompressionPolicy]
 * so they can be tuned without code changes.
 */
expect fun compressPhoto(
    sourcePath: String,
    destPath: String,
    quality: Int,
    maxDimension: Int,
): Boolean

