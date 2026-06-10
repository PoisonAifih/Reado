package com.vivivy.reado

import android.graphics.RectF
import android.util.Log

enum class ScanMode {
    PORTRAIT,
    BOOK
}

data class NormalizedZone(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val label: String = ""
) {
    fun containsCenter(rect: RectF, imgWidth: Float, imgHeight: Float): Boolean {
        val cx = rect.centerX()
        val cy = rect.centerY()
        return cx >= left * imgWidth && cx <= right * imgWidth &&
            cy >= top * imgHeight && cy <= bottom * imgHeight
    }

    fun blockFullyInside(rect: RectF, imgWidth: Float, imgHeight: Float): Boolean {
        val zTop = top * imgHeight
        val zBottom = bottom * imgHeight
        val zLeft = left * imgWidth
        val zRight = right * imgWidth
        return rect.top >= zTop &&
            rect.bottom <= zBottom &&
            rect.left >= zLeft &&
            rect.right <= zRight
    }

    fun overlapRatio(rect: RectF, imgWidth: Float, imgHeight: Float): Float {
        val zLeft = left * imgWidth
        val zTop = top * imgHeight
        val zRight = right * imgWidth
        val zBottom = bottom * imgHeight

        val overlapLeft = maxOf(rect.left, zLeft)
        val overlapTop = maxOf(rect.top, zTop)
        val overlapRight = minOf(rect.right, zRight)
        val overlapBottom = minOf(rect.bottom, zBottom)

        if (overlapRight <= overlapLeft || overlapBottom <= overlapTop) return 0f

        val overlapArea = (overlapRight - overlapLeft) * (overlapBottom - overlapTop)
        val blockArea = rect.width() * rect.height()
        if (blockArea <= 0f) return 0f
        return overlapArea / blockArea
    }

    /** Center in zone + at least half the line sits inside (handles slight OCR overshoot). */
    fun toImageRect(imgWidth: Float, imgHeight: Float): RectF {
        return RectF(
            left * imgWidth,
            top * imgHeight,
            right * imgWidth,
            bottom * imgHeight
        )
    }
}

object ScanModeConfig {
    val portraitZones = listOf(
        NormalizedZone(0.10f, 0.32f, 0.90f, 0.82f, "main")
    )

    val bookZones = listOf(
        NormalizedZone(0.05f, 0.14f, 0.46f, 0.86f, "left"),
        NormalizedZone(0.54f, 0.14f, 0.95f, 0.86f, "right")
    )

    fun zonesFor(mode: ScanMode) = when (mode) {
        ScanMode.PORTRAIT -> portraitZones
        ScanMode.BOOK -> bookZones
    }
}

object ScanProcessor {
    private const val TAG = "ScanProcessor"

    fun process(
        rawResults: List<ScanResult>,
        imgWidth: Float,
        imgHeight: Float,
        mode: ScanMode
    ): List<ScanResult> {
        if (rawResults.isEmpty()) {
            Log.d(TAG, "OCR returned 0 raw blocks")
            return emptyList()
        }

        Log.d(TAG, "OCR raw blocks: ${rawResults.size}, image ${imgWidth}x$imgHeight, mode=$mode")

        val lines = rawResults.filterNot { isUiNoise(it, imgHeight) }
        val zones = ScanModeConfig.zonesFor(mode)

        if (mode == ScanMode.BOOK) {
            return processBook(lines, imgWidth, imgHeight, zones)
        }

        val merged = clusterBlocks(lines, imgWidth, imgHeight)
        val filtered = filterPortrait(merged, imgWidth, imgHeight, zones.first(), strict = true)
        if (filtered.isNotEmpty()) {
            Log.d(TAG, "Portrait strict kept ${filtered.size} blocks")
            return filtered.sortedWith(compareBy({ it.rect.top }, { it.rect.left }))
        }

        val relaxed = filterPortrait(merged, imgWidth, imgHeight, zones.first(), strict = false)
        Log.d(TAG, "Portrait relaxed kept ${relaxed.size} blocks")
        return relaxed.sortedWith(compareBy({ it.rect.top }, { it.rect.left }))
    }

    private fun processBook(
        lines: List<ScanResult>,
        imgWidth: Float,
        imgHeight: Float,
        zones: List<NormalizedZone>
    ): List<ScanResult> {
        val gutterLeft = imgWidth * 0.47f
        val gutterRight = imgWidth * 0.53f

        val pages = zones.mapNotNull { zone ->
            mergePageZone(zone, lines, imgWidth, imgHeight, gutterLeft, gutterRight)
        }

        Log.d(TAG, "Book mode merged ${pages.size} page(s) from ${lines.size} OCR lines")
        return pages
    }

    /** Collect all lines inside one page zone and merge into a single page block. */
    private fun mergePageZone(
        zone: NormalizedZone,
        lines: List<ScanResult>,
        imgWidth: Float,
        imgHeight: Float,
        gutterLeft: Float,
        gutterRight: Float
    ): ScanResult? {
        val pageLines = lines.filter { line ->
            val cx = line.rect.centerX()
            if (cx in gutterLeft..gutterRight) return@filter false
            zone.containsCenter(line.rect, imgWidth, imgHeight) &&
                !isBookMarginNoise(line, imgWidth, imgHeight)
        }

        if (pageLines.isEmpty()) return null

        val bodyFont = estimateBodyFontSize(pageLines)
        val minFont = bodyFont * 0.45f
        val bodyLines = pageLines.filter { it.estimatedFontSize >= minFont }.ifEmpty { pageLines }

        val sorted = bodyLines.sortedWith(compareBy({ it.rect.top }, { it.rect.left }))
        val fullText = sorted.joinToString(" ") { it.text.trim() }.trim()
        if (fullText.isEmpty()) return null

        return ScanResult(
            text = fullText,
            rect = zone.toImageRect(imgWidth, imgHeight),
            estimatedFontSize = bodyLines.maxOf { it.estimatedFontSize }
        )
    }

    private fun isBookMarginNoise(line: ScanResult, imgWidth: Float, imgHeight: Float): Boolean {
        val cy = line.rect.centerY() / imgHeight
        val cx = line.rect.centerX() / imgWidth
        val isTiny = line.text.trim().length <= 3
        return isTiny && (cy < 0.10f || cy > 0.92f || cx < 0.04f || cx > 0.96f)
    }

    private fun isUiNoise(result: ScanResult, imgHeight: Float): Boolean {
        val text = result.text.trim()
        if (text.isEmpty()) return true
        if (text.length <= 1) return true
        if (text.all { it.isDigit() }) return true

        val cy = result.rect.centerY() / imgHeight
        if (cy < 0.28f && result.estimatedFontSize < imgHeight * 0.025f) return true

        return false
    }

    private fun clusterBlocks(
        rawResults: List<ScanResult>,
        imgWidth: Float,
        imgHeight: Float
    ): List<ScanResult> {
        val thresholdX = imgWidth * 0.12f
        val thresholdY = imgHeight * 0.04f
        val mergedResults = mutableListOf<ScanResult>()
        val used = BooleanArray(rawResults.size)

        for (i in rawResults.indices) {
            if (used[i]) continue
            val currentRect = RectF(rawResults[i].rect)
            var currentText = rawResults[i].text
            var currentFontSize = rawResults[i].estimatedFontSize
            used[i] = true

            var mergedAny = true
            while (mergedAny) {
                mergedAny = false
                for (j in rawResults.indices) {
                    if (used[j]) continue
                    val otherRect = rawResults[j].rect
                    val dx = maxOf(0f, maxOf(currentRect.left - otherRect.right, otherRect.left - currentRect.right))
                    val dy = maxOf(0f, maxOf(currentRect.top - otherRect.bottom, otherRect.top - currentRect.bottom))
                    if (dx < thresholdX && dy < thresholdY) {
                        currentText = if (otherRect.top < currentRect.top) {
                            rawResults[j].text + " " + currentText
                        } else {
                            currentText + " " + rawResults[j].text
                        }
                        currentRect.union(otherRect)
                        currentFontSize = maxOf(currentFontSize, rawResults[j].estimatedFontSize)
                        used[j] = true
                        mergedAny = true
                    }
                }
            }
            mergedResults.add(ScanResult(currentText, currentRect, currentFontSize))
        }
        return mergedResults
    }

    private fun filterPortrait(
        merged: List<ScanResult>,
        imgWidth: Float,
        imgHeight: Float,
        zone: NormalizedZone,
        strict: Boolean
    ): List<ScanResult> {
        val totalArea = imgWidth * imgHeight
        val minAreaRatio = if (strict) 0.002f else 0.0012f

        val inZone = merged.filter { result ->
            val area = result.rect.width() * result.rect.height()
            if (area <= totalArea * minAreaRatio) return@filter false
            if (isUiNoise(result, imgHeight)) return@filter false

            if (strict) zone.blockFullyInside(result.rect, imgWidth, imgHeight)
            else zone.containsCenter(result.rect, imgWidth, imgHeight)
        }

        if (inZone.isEmpty()) return emptyList()

        val bodyFont = estimateBodyFontSize(inZone)
        val minFont = if (strict) bodyFont * 0.78f else bodyFont * 0.60f

        return inZone.filter { result ->
            result.estimatedFontSize >= minFont && !isToolbarLine(result, imgHeight, bodyFont)
        }
    }

    private fun estimateBodyFontSize(blocks: List<ScanResult>): Float {
        if (blocks.isEmpty()) return 0f
        val sorted = blocks.map { it.estimatedFontSize }.sorted()
        return sorted[sorted.size / 2]
    }

    private fun isToolbarLine(result: ScanResult, imgHeight: Float, bodyFont: Float): Boolean {
        val cy = result.rect.centerY() / imgHeight
        val isMuchSmaller = result.estimatedFontSize < bodyFont * 0.70f
        return cy < 0.38f && isMuchSmaller
    }
}
