package com.zcam.camera

import androidx.camera.core.ImageProxy
import com.zcam.core.domain.config.EventDetectionSensitivity
import com.zcam.core.domain.recording.RecordingEvent
import com.zcam.core.domain.recording.RecordingEventType
import com.zcam.core.logging.ZCamLogger
import kotlin.math.abs
import kotlin.math.max

internal data class LumaFrame(
    val width: Int,
    val height: Int,
    val pixels: IntArray
)

internal class SceneEventDetector(
    private val logger: ZCamLogger
) {
    private var previous = IntArray(0)
    private var background = FloatArray(0)
    private var warmupFrames = 0
    private var objectHits = 0
    private var tamperHits = 0
    private var previousMean = 0f
    private var baselineEdgeEnergy = 0f
    private var lastObjectEventMs = 0L
    private var lastTamperEventMs = 0L
    private var sensitivity = SensitivityProfile.BALANCED

    fun updateSensitivity(next: EventDetectionSensitivity) {
        sensitivity = SensitivityProfile.from(next)
        objectHits = 0
        tamperHits = 0
    }

    fun currentSensitivity(): EventDetectionSensitivity = sensitivity.setting

    fun analyze(image: ImageProxy, eventEpochMs: Long): RecordingEvent? =
        analyze(sampleLuma(image), eventEpochMs)

    internal fun analyze(frame: LumaFrame, eventEpochMs: Long): RecordingEvent? {
        if (frame.width <= 0 || frame.height <= 0 || frame.pixels.size != frame.width * frame.height) return null
        if (background.size != frame.pixels.size) {
            reset(frame)
            return null
        }

        val mean = frame.pixels.average().toFloat()
        val edgeEnergy = edgeEnergy(frame)
        if (warmupFrames < WARMUP_FRAME_COUNT) {
            updateBackground(frame.pixels, WARMUP_LEARNING_RATE)
            previous = frame.pixels.copyOf()
            previousMean = mean
            baselineEdgeEnergy = if (baselineEdgeEnergy == 0f) edgeEnergy else lerp(baselineEdgeEnergy, edgeEnergy, 0.2f)
            warmupFrames += 1
            return null
        }

        val changed = BooleanArray(frame.pixels.size)
        var previousDeltaTotal = 0f
        var structuralDeltaTotal = 0f
        var changedDeltaTotal = 0f
        var changedCount = 0
        var previousChangedCount = 0
        for (index in frame.pixels.indices) {
            val value = frame.pixels[index]
            val backgroundDelta = abs(value - background[index])
            val previousDelta = abs(value - previous[index]).toFloat()
            val structuralDelta = abs((value - mean) - (previous[index] - previousMean))
            previousDeltaTotal += previousDelta
            structuralDeltaTotal += structuralDelta
            if (backgroundDelta >= sensitivity.pixelDeltaThreshold) {
                changed[index] = true
                changedCount += 1
                changedDeltaTotal += backgroundDelta
            }
            if (previousDelta >= sensitivity.pixelDeltaThreshold) previousChangedCount += 1
        }

        val sampleCount = frame.pixels.size.toFloat()
        val changedRatio = changedCount / sampleCount
        val previousChangedRatio = previousChangedCount / sampleCount
        val averageChangedDelta = if (changedCount == 0) 0f else changedDeltaTotal / changedCount
        val averagePreviousDelta = previousDeltaTotal / sampleCount
        val structuralDelta = structuralDeltaTotal / sampleCount
        val largestRegionRatio = largestConnectedRegionRatio(
            denoise(changed, frame.width, frame.height),
            frame.width,
            frame.height
        )

        val coverAppearance = mean <= COVERED_MEAN_LUMA ||
            edgeEnergy <= max(MIN_COVERED_EDGE_ENERGY, baselineEdgeEnergy * COVERED_EDGE_RATIO)
        val covered = coverAppearance &&
            (tamperHits > 0 || abs(mean - previousMean) >= COVERED_CHANGE_DELTA)
        val movedBetweenFrames = previousChangedRatio >= GLOBAL_CHANGE_RATIO &&
            averagePreviousDelta >= GLOBAL_PREVIOUS_DELTA &&
            structuralDelta >= GLOBAL_STRUCTURAL_DELTA
        val movedScenePersisted = tamperHits > 0 &&
            changedRatio >= GLOBAL_CHANGE_RATIO &&
            averageChangedDelta >= GLOBAL_PREVIOUS_DELTA
        val moved = movedBetweenFrames || movedScenePersisted
        val tamperCandidate = covered || moved
        tamperHits = if (tamperCandidate) tamperHits + 1 else 0

        val objectCandidate = !tamperCandidate &&
            changedRatio >= sensitivity.changedRatioThreshold &&
            largestRegionRatio >= sensitivity.regionRatioThreshold &&
            largestRegionRatio <= MAX_OBJECT_REGION_RATIO &&
            averageChangedDelta >= sensitivity.averageDeltaThreshold
        objectHits = if (objectCandidate) objectHits + 1 else 0

        previous = frame.pixels.copyOf()
        previousMean = mean
        if (!tamperCandidate) {
            baselineEdgeEnergy = lerp(baselineEdgeEnergy, edgeEnergy, EDGE_BASELINE_LEARNING_RATE)
            updateBackgroundSelective(frame.pixels, changed)
        }

        if (tamperHits >= TAMPER_HITS_REQUIRED && eventEpochMs - lastTamperEventMs >= TAMPER_COOLDOWN_MS) {
            val reason = if (covered) "covered" else "moved"
            val confidence = if (covered) {
                ((1f - mean / 255f) * 100f).toInt()
            } else {
                (structuralDelta * 4f).toInt()
            }.coerceIn(1, 100)
            lastTamperEventMs = eventEpochMs
            tamperHits = 0
            objectHits = 0
            resetBackground(frame)
            logger.d("Camera tamper event reason=$reason changedRatio=$previousChangedRatio structuralDelta=$structuralDelta")
            return RecordingEvent(
                epochMs = eventEpochMs,
                confidencePercent = confidence,
                source = "camera_tamper:$reason",
                type = RecordingEventType.CAMERA_TAMPER
            )
        }

        if (objectHits >= sensitivity.hitsRequired && eventEpochMs - lastObjectEventMs >= sensitivity.cooldownMs) {
            lastObjectEventMs = eventEpochMs
            objectHits = 0
            val confidence = (largestRegionRatio * 220f + averageChangedDelta * 1.4f).toInt().coerceIn(1, 100)
            logger.d(
                "Object motion event sensitivity=${sensitivity.setting.wireName} changedRatio=$changedRatio " +
                    "largestRegionRatio=$largestRegionRatio averageChangedDelta=$averageChangedDelta"
            )
            return RecordingEvent(
                epochMs = eventEpochMs,
                confidencePercent = confidence,
                source = "object_motion:${sensitivity.setting.wireName}",
                type = RecordingEventType.OBJECT_MOTION
            )
        }
        return null
    }

    private fun reset(frame: LumaFrame) {
        previous = frame.pixels.copyOf()
        background = FloatArray(frame.pixels.size) { frame.pixels[it].toFloat() }
        previousMean = frame.pixels.average().toFloat()
        baselineEdgeEnergy = edgeEnergy(frame)
        warmupFrames = 1
        objectHits = 0
        tamperHits = 0
    }

    private fun resetBackground(frame: LumaFrame) {
        background = FloatArray(frame.pixels.size) { frame.pixels[it].toFloat() }
        warmupFrames = 0
    }

    private fun updateBackground(values: IntArray, rate: Float) {
        for (index in values.indices) background[index] = lerp(background[index], values[index].toFloat(), rate)
    }

    private fun updateBackgroundSelective(values: IntArray, changed: BooleanArray) {
        for (index in values.indices) {
            val rate = if (changed[index]) FOREGROUND_LEARNING_RATE else BACKGROUND_LEARNING_RATE
            background[index] = lerp(background[index], values[index].toFloat(), rate)
        }
    }

    private fun denoise(mask: BooleanArray, width: Int, height: Int): BooleanArray {
        val result = BooleanArray(mask.size)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val index = y * width + x
                if (!mask[index]) continue
                var neighbours = 0
                for (dy in -1..1) for (dx in -1..1) {
                    if ((dx != 0 || dy != 0) && mask[(y + dy) * width + x + dx]) neighbours += 1
                }
                result[index] = neighbours >= MIN_CHANGED_NEIGHBOURS
            }
        }
        return result
    }

    private fun largestConnectedRegionRatio(mask: BooleanArray, width: Int, height: Int): Float {
        val visited = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        var largest = 0
        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var size = 0
            while (head < tail) {
                val current = queue[head++]
                size += 1
                val x = current % width
                val y = current / width
                if (x > 0) tail = enqueue(current - 1, mask, visited, queue, tail)
                if (x + 1 < width) tail = enqueue(current + 1, mask, visited, queue, tail)
                if (y > 0) tail = enqueue(current - width, mask, visited, queue, tail)
                if (y + 1 < height) tail = enqueue(current + width, mask, visited, queue, tail)
            }
            largest = max(largest, size)
        }
        return largest / mask.size.toFloat()
    }

    private fun enqueue(index: Int, mask: BooleanArray, visited: BooleanArray, queue: IntArray, tail: Int): Int {
        if (!mask[index] || visited[index]) return tail
        visited[index] = true
        queue[tail] = index
        return tail + 1
    }

    private fun edgeEnergy(frame: LumaFrame): Float {
        if (frame.width < 2 || frame.height < 2) return 0f
        var total = 0L
        var count = 0
        for (y in 1 until frame.height) for (x in 1 until frame.width) {
            val index = y * frame.width + x
            total += abs(frame.pixels[index] - frame.pixels[index - 1])
            total += abs(frame.pixels[index] - frame.pixels[index - frame.width])
            count += 2
        }
        return if (count == 0) 0f else total / count.toFloat()
    }

    private fun sampleLuma(image: ImageProxy): LumaFrame {
        val plane = image.planes.firstOrNull() ?: return LumaFrame(0, 0, IntArray(0))
        val buffer = plane.buffer.duplicate()
        val sourceWidth = image.width
        val sourceHeight = image.height
        if (sourceWidth <= 0 || sourceHeight <= 0 || !buffer.hasRemaining()) return LumaFrame(0, 0, IntArray(0))
        val width = minOf(SAMPLE_COLUMNS, sourceWidth)
        val height = minOf(SAMPLE_ROWS, sourceHeight)
        val values = IntArray(width * height)
        val base = buffer.position()
        for (y in 0 until height) {
            val sourceY = y * (sourceHeight - 1) / max(1, height - 1)
            for (x in 0 until width) {
                val sourceX = x * (sourceWidth - 1) / max(1, width - 1)
                val index = base + sourceY * plane.rowStride + sourceX * plane.pixelStride.coerceAtLeast(1)
                if (index in base until buffer.limit()) values[y * width + x] = buffer.get(index).toInt() and 0xff
            }
        }
        return LumaFrame(width, height, values)
    }

    private fun lerp(from: Float, to: Float, rate: Float): Float = from + (to - from) * rate

    private enum class SensitivityProfile(
        val setting: EventDetectionSensitivity,
        val hitsRequired: Int,
        val pixelDeltaThreshold: Float,
        val averageDeltaThreshold: Float,
        val changedRatioThreshold: Float,
        val regionRatioThreshold: Float,
        val cooldownMs: Long
    ) {
        LOW(EventDetectionSensitivity.LOW, 3, 28f, 9f, 0.020f, 0.018f, 12_000L),
        BALANCED(EventDetectionSensitivity.BALANCED, 2, 20f, 7f, 0.012f, 0.009f, 8_000L),
        HIGH(EventDetectionSensitivity.HIGH, 2, 14f, 5f, 0.006f, 0.004f, 5_000L);

        companion object {
            fun from(value: EventDetectionSensitivity): SensitivityProfile = entries.first { it.setting == value }
        }
    }

    private companion object {
        const val SAMPLE_COLUMNS = 80
        const val SAMPLE_ROWS = 45
        const val WARMUP_FRAME_COUNT = 8
        const val WARMUP_LEARNING_RATE = 0.35f
        const val BACKGROUND_LEARNING_RATE = 0.025f
        const val FOREGROUND_LEARNING_RATE = 0.002f
        const val EDGE_BASELINE_LEARNING_RATE = 0.02f
        const val MIN_CHANGED_NEIGHBOURS = 2
        const val MAX_OBJECT_REGION_RATIO = 0.60f
        const val GLOBAL_CHANGE_RATIO = 0.68f
        const val GLOBAL_PREVIOUS_DELTA = 17f
        const val GLOBAL_STRUCTURAL_DELTA = 10f
        const val COVERED_MEAN_LUMA = 18f
        const val COVERED_CHANGE_DELTA = 12f
        const val COVERED_EDGE_RATIO = 0.20f
        const val MIN_COVERED_EDGE_ENERGY = 2.2f
        const val TAMPER_HITS_REQUIRED = 2
        const val TAMPER_COOLDOWN_MS = 15_000L
    }
}
