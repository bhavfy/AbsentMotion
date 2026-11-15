package app.absentmotion.editor

import app.absentmotion.R
import app.absentmotion.audio.AudioPeaks
import app.absentmotion.audio.Waveforms
import app.absentmotion.audio.soundUri
import app.absentmotion.i18n.AppText
import app.absentmotion.model.Layer
import app.absentmotion.model.Scene
import app.absentmotion.model.isMask
import app.absentmotion.model.mediaTimeMs
import app.absentmotion.model.remapped
import app.absentmotion.model.trimmedTo
import app.absentmotion.platform.Context
import kotlinx.coroutines.delay

private const val SILENCE_THRESHOLD = 0.08f
private const val MIN_SILENCE_MS = 500
private const val EDGE_PAD_MS = 120
private const val MIN_KEEP_MS = 120
private const val STEP_MS = 50
private const val BRIDGE_MS = 200
private const val PEAK_WAIT_MS = 60_000L

internal fun findSilentRanges(peaks: AudioPeaks, layer: Layer): List<IntRange> {
    val start = layer.startTime
    val end = layer.endTime
    if (end <= start) return emptyList()
    val raw = mutableListOf<IntRange>()
    var runStart: Int? = null
    var t = start
    while (t < end) {
        val to = minOf(t + STEP_MS, end)
        val loud = peaks.peak(layer.mediaTimeMs(t.toDouble()), layer.mediaTimeMs(to.toDouble()))
        if (loud < SILENCE_THRESHOLD) {
            if (runStart == null) runStart = t
        } else {
            runStart?.let { raw += it..t; runStart = null }
        }
        t = to
    }
    runStart?.let { raw += it..end }
    if (raw.isEmpty()) return emptyList()
    val bridged = mutableListOf<IntRange>()
    for (range in raw) {
        val last = bridged.lastOrNull()
        if (last != null && range.first - last.last <= BRIDGE_MS) bridged[bridged.lastIndex] = last.first..range.last
        else bridged += range
    }
    return bridged.mapNotNull { range ->
        val a = (range.first + EDGE_PAD_MS).coerceAtMost(range.last)
        val b = (range.last - EDGE_PAD_MS).coerceAtLeast(a)
        (a..b).takeIf { b - a >= MIN_SILENCE_MS }
    }
}

internal fun Scene.autoCutLayer(layer: Layer, silences: List<IntRange>): Scene? {
    val start = layer.startTime
    val end = layer.endTime
    if (end <= start || silences.isEmpty()) return null
    val keep = mutableListOf<IntRange>()
    var cursor = start
    for (s in silences.sortedBy { it.first }) {
        val a = s.first.coerceIn(start, end)
        val b = s.last.coerceIn(start, end)
        if (a > cursor) keep += cursor..a
        cursor = maxOf(cursor, b)
    }
    if (cursor < end) keep += cursor..end
    val kept = keep.filter { it.last - it.first >= MIN_KEEP_MS }
    if (kept.isEmpty()) return null
    if (kept.size == 1 && kept[0].first == start && kept[0].last == end) return null
    val index = layers.indexOfFirst { it.id == layer.id }
    if (index < 0) return null
    var maskEnd = index + 1
    while (maskEnd < layers.size && layers.isMask(maskEnd)) maskEnd++
    val block = layers.subList(index, maskEnd)
    var at = start
    val rebuilt = mutableListOf<Layer>()
    for ((i, range) in kept.withIndex()) {
        val newStart = at
        for (member in block) {
            val piece = member.trimmedTo(range.first, range.last).movedTo(newStart)
            rebuilt += if (i == 0) piece else piece.withId(Layer.newId())
        }
        at = newStart + (range.last - range.first)
    }
    return withLayers(layers.subList(0, index) + rebuilt + layers.subList(maskEnd, layers.size))
}

suspend fun EditorState.autoCutNote(context: Context): String {
    val layer = selected ?: return AppText.get(R.string.auto_cut_select_video)
    val uri = layer.soundUri ?: return AppText.get(R.string.auto_cut_select_video)
    if (layer.remapped) return AppText.get(R.string.auto_cut_unsupported)
    Waveforms.request(context, uri)
    val deadline = System.currentTimeMillis() + PEAK_WAIT_MS
    var peaks = Waveforms.peaks(uri)
    while ((peaks == null || peaks.data.isEmpty()) && System.currentTimeMillis() < deadline) {
        delay(200)
        peaks = Waveforms.peaks(uri)
    }
    if (peaks == null || peaks.data.isEmpty()) return AppText.get(R.string.auto_cut_no_audio)
    val silences = findSilentRanges(peaks, layer)
    if (silences.isEmpty()) return AppText.get(R.string.auto_cut_no_silence)
    val cut = scene.autoCutLayer(layer, silences) ?: return AppText.get(R.string.auto_cut_no_silence)
    val removedMs = silences.sumOf { it.last - it.first }
    edit { cut }
    val seconds = removedMs / 1000.0
    val secondsText = if (seconds >= 10) "${seconds.toInt()}s" else "%.1fs".format(seconds)
    return AppText.get(R.string.auto_cut_removed, silences.size.toString(), secondsText)
}
