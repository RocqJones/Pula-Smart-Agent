package com.jonesmb.pulasmartagent.platform.media

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.writeToFile
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation

@OptIn(ExperimentalForeignApi::class)
actual fun compressPhoto(
    sourcePath: String,
    destPath: String,
    quality: Int,
    maxDimension: Int,
): Boolean = runCatching {
    val original = UIImage.imageWithContentsOfFile(sourcePath) ?: return false

    val origWidth = original.size.useContents { width }
    val origHeight = original.size.useContents { height }
    val scale = maxOf(origWidth, origHeight) / maxDimension.toDouble()

    val targetWidth = if (scale > 1.0) origWidth / scale else origWidth
    val targetHeight = if (scale > 1.0) origHeight / scale else origHeight

    UIGraphicsBeginImageContextWithOptions(CGSizeMake(targetWidth, targetHeight), false, 1.0)
    original.drawInRect(
        platform.CoreGraphics.CGRectMake(0.0, 0.0, targetWidth, targetHeight)
    )
    val resized = UIGraphicsGetImageFromCurrentImageContext()
    UIGraphicsEndImageContext()

    val jpeg: NSData = UIImageJPEGRepresentation(resized ?: original, quality / 100.0) ?: return false
    jpeg.writeToFile(destPath, atomically = true)
}.isSuccess

