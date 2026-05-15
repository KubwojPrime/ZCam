package com.zcam.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.VideoView
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.zcam.core.domain.config.PreviewTransport
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

@Composable
internal fun ActiveActionButton(
    text: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false
) {
    val colors = when {
        active && destructive -> ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError
        )
        active -> ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.tertiary,
            contentColor = MaterialTheme.colorScheme.onTertiary
        )
        destructive -> ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        )
        else -> ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = colors
    ) {
        Text(text)
    }
}

@Composable
internal fun LivePreviewSurface(
    previewTransport: PreviewTransport,
    previewStreamUrl: String,
    previewMjpegFallbackUrl: String,
    previewLabel: String,
    fallbackFrameJpeg: ByteArray? = null,
    modifier: Modifier = Modifier,
    zoomable: Boolean = false,
    containPreview: Boolean = false
) {
    val fallbackBitmap = remember(fallbackFrameJpeg) {
        fallbackFrameJpeg?.takeIf { it.isNotEmpty() }?.let { jpeg ->
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        }
    }

    if (previewStreamUrl.isBlank()) {
        Box(
            modifier = modifier
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = previewLabel.ifBlank { "Preview unavailable" },
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(12.dp)
            )
        }
        return
    }

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
    ) {
        if (fallbackBitmap != null) {
            Image(
                bitmap = fallbackBitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = if (containPreview) ContentScale.Fit else ContentScale.Crop
            )
        }

        if (previewTransport == PreviewTransport.H264) {
            var zoomScale by remember(previewStreamUrl, zoomable) { mutableStateOf(1f) }
            var zoomOffsetX by remember(previewStreamUrl, zoomable) { mutableStateOf(0f) }
            var zoomOffsetY by remember(previewStreamUrl, zoomable) { mutableStateOf(0f) }
            var renderState by remember(previewStreamUrl) {
                mutableStateOf(
                    H264PreviewRenderState(
                        statusLabel = "Preview loading",
                        diagnosticsLabel = "decoder: waiting for socket",
                        tone = StatusTone.WARNING
                    )
                )
            }
            val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
                if (!zoomable) return@rememberTransformableState
                val nextScale = (zoomScale * zoomChange).coerceIn(1f, 4f)
                if (nextScale <= 1.02f) {
                    zoomScale = 1f
                    zoomOffsetX = 0f
                    zoomOffsetY = 0f
                } else {
                    zoomScale = nextScale
                    zoomOffsetX += panChange.x
                    zoomOffsetY += panChange.y
                }
            }
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = zoomScale,
                        scaleY = zoomScale,
                        translationX = zoomOffsetX,
                        translationY = zoomOffsetY
                    )
                    .transformable(
                        state = transformableState,
                        enabled = zoomable
                    ),
                factory = { context ->
                    H264PreviewPlayerView(context).apply {
                        statusListener = { nextState -> renderState = nextState }
                        bindPreviewSocketUrl(previewStreamUrl)
                    }
                },
                update = { playerView ->
                    playerView.statusListener = { nextState -> renderState = nextState }
                    playerView.bindPreviewSocketUrl(previewStreamUrl)
                }
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StatusChip(label = renderState.statusLabel, tone = renderState.tone, compact = true)
                StatusChip(label = renderState.diagnosticsLabel, tone = StatusTone.NEUTRAL, compact = true)
                if (previewMjpegFallbackUrl.isNotBlank()) {
                    StatusChip(label = "Fallback available: MJPEG", tone = StatusTone.NEUTRAL, compact = true)
                }
            }
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.javaScriptEnabled = false
                        settings.loadsImagesAutomatically = true
                        settings.cacheMode = WebSettings.LOAD_NO_CACHE
                        settings.mediaPlaybackRequiresUserGesture = false
                        isHorizontalScrollBarEnabled = false
                        isVerticalScrollBarEnabled = false
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        webViewClient = object : WebViewClient() {
                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: WebResourceError
                            ) {
                                view.postDelayed({ view.reload() }, PREVIEW_RETRY_DELAY_MS)
                            }

                            override fun onReceivedHttpError(
                                view: WebView,
                                request: WebResourceRequest,
                                errorResponse: WebResourceResponse
                            ) {
                                view.postDelayed({ view.reload() }, PREVIEW_RETRY_DELAY_MS)
                            }
                        }
                    }
                },
                update = { webView ->
                    webView.settings.setSupportZoom(zoomable)
                    webView.settings.builtInZoomControls = zoomable
                    webView.settings.displayZoomControls = false
                    webView.settings.useWideViewPort = zoomable
                    webView.settings.loadWithOverviewMode = zoomable
                    val objectFit = if (containPreview) "contain" else "cover"
                    val imageWidth = if (zoomable) "100%" else "100vw"
                    val imageHeight = if (containPreview) "auto" else "100vh"
                    val html = """
                        <html>
                          <head>
                            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=8.0, user-scalable=yes" />
                          </head>
                          <body style="margin:0;background:transparent;overflow:hidden;">
                            <div style="width:100vw;min-height:100vh;display:flex;align-items:center;justify-content:center;background:transparent;">
                              <img src="$previewStreamUrl" style="display:block;width:$imageWidth;height:$imageHeight;object-fit:$objectFit;" />
                            </div>
                          </body>
                        </html>
                    """.trimIndent()
                    if (webView.tag != html) {
                        webView.tag = html
                        webView.loadDataWithBaseURL(previewStreamUrl, html, "text/html", "utf-8", null)
                    }
                }
            )
        }
    }
}

@Composable
internal fun ClientCameraControlsSection(
    state: ZCamUiState,
    onAction: (ZCamUiAction) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Camera controls",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            StatusChip(label = state.cameraLensLabel, tone = state.cameraLensTone)
            StatusChip(label = state.eventSensitivityLabel, tone = StatusTone.NEUTRAL)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ActiveActionButton(
                    text = "Torch ON",
                    active = state.clientTorchEnabled,
                    onClick = { onAction(ZCamUiAction.SetTorchEnabled(true)) },
                    modifier = Modifier.weight(1f)
                )
                ActiveActionButton(
                    text = "Torch OFF",
                    active = !state.clientTorchEnabled,
                    onClick = { onAction(ZCamUiAction.SetTorchEnabled(false)) },
                    modifier = Modifier.weight(1f),
                    destructive = true
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ActiveActionButton(
                    text = "Night ON",
                    active = state.clientNightModeEnabled,
                    onClick = { onAction(ZCamUiAction.SetNightModeEnabled(true)) },
                    modifier = Modifier.weight(1f),
                    enabled = state.clientLowLightBoostSupported
                )
                ActiveActionButton(
                    text = "Night OFF",
                    active = !state.clientNightModeEnabled,
                    onClick = { onAction(ZCamUiAction.SetNightModeEnabled(false)) },
                    modifier = Modifier.weight(1f),
                    destructive = true
                )
            }
            val zoomRatio = state.clientZoomRatio.coerceAtLeast(1f)
            val zoomSupported = state.clientMaxZoomRatio > 1.01f || state.clientZoomLinear > 0f
            Text(
                text = if (zoomSupported) {
                    "Zoom ${String.format(Locale.US, "%.1f", zoomRatio)}x"
                } else {
                    "Zoom fixed at ${String.format(Locale.US, "%.1f", zoomRatio)}x"
                },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ActiveActionButton(
                    text = "Zoom -",
                    active = false,
                    onClick = { onAction(ZCamUiAction.AdjustClientZoom(deltaLinear = -0.12f)) },
                    modifier = Modifier.weight(1f),
                    enabled = zoomSupported && state.clientZoomLinear > 0f
                )
                ActiveActionButton(
                    text = "Reset",
                    active = state.clientZoomLinear <= 0.01f,
                    onClick = { onAction(ZCamUiAction.ResetClientZoom) },
                    modifier = Modifier.weight(1f),
                    enabled = zoomSupported
                )
                ActiveActionButton(
                    text = "Zoom +",
                    active = false,
                    onClick = { onAction(ZCamUiAction.AdjustClientZoom(deltaLinear = 0.12f)) },
                    modifier = Modifier.weight(1f),
                    enabled = zoomSupported && state.clientZoomLinear < 0.99f
                )
            }
            Text(
                text = if (state.clientLowLightBoostSupported) {
                    "Low-light boost available on the server camera."
                } else {
                    "Low-light boost unsupported on the server camera."
                },
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private const val PREVIEW_RETRY_DELAY_MS = 1_000L

@Composable
internal fun RecordingsStudioScreen(
    state: ZCamUiState,
    onAction: (ZCamUiAction) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (state.mode != ZCamMode.CLIENT) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Text(
                    text = "Recordings timeline is available from client mode, where the app can pull remote clips and event markers from the server.",
                    modifier = Modifier.padding(12.dp)
                )
            }
            return
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Recordings",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Load a time range, tap a segment or event marker, then scrub the selected recording below.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = state.recordings.fromInput,
                    onValueChange = { onAction(ZCamUiAction.RecordingsFromChanged(it)) },
                    label = { Text("From") },
                    singleLine = true
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = state.recordings.toInput,
                    onValueChange = { onAction(ZCamUiAction.RecordingsToChanged(it)) },
                    label = { Text("To") },
                    singleLine = true
                )
                ActiveActionButton(
                    text = if (state.recordings.loading) "Loading..." else "Load recordings",
                    active = state.recordings.items.isNotEmpty(),
                    onClick = { onAction(ZCamUiAction.FetchRecordings) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    enabled = !state.recordings.loading
                )
                if (state.recordings.resultMessage.isNotBlank()) {
                    Text(
                        text = state.recordings.resultMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = resultToneColor(state.recordings.resultTone)
                    )
                }
                if (state.recordings.downloadMessage.isNotBlank()) {
                    Text(
                        text = state.recordings.downloadMessage,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        RecordingPlayerCard(state = state, onAction = onAction)
        RecordingTimelineCard(state = state, onAction = onAction)
        RecordingSegmentsList(state = state, onAction = onAction)
    }
}

@Composable
private fun RecordingPlayerCard(
    state: ZCamUiState,
    onAction: (ZCamUiAction) -> Unit
) {
    val selectedUrl = state.recordings.selectedPlaybackUrl
    val selectedOffsetMs = state.recordings.selectedPlaybackOffsetMs
    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }
    var mediaPlayerRef by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    var durationMs by remember(selectedUrl) { mutableLongStateOf(0L) }
    var positionMs by remember(selectedUrl) { mutableLongStateOf(selectedOffsetMs) }
    var scrubPositionMs by remember(selectedUrl) { mutableLongStateOf(selectedOffsetMs) }
    var scrubbing by remember { mutableStateOf(false) }
    var initialSeekMs by remember(selectedUrl, selectedOffsetMs) { mutableLongStateOf(selectedOffsetMs) }
    var isPlaying by remember(selectedUrl) { mutableStateOf(false) }
    var playbackSpeed by remember(selectedUrl) { mutableStateOf(1f) }
    val selectedItem = state.recordings.items.firstOrNull { it.fileName == state.recordings.selectedFileName }
    val selectedEvents = state.recordings.events
        .filter { it.recordingFileName == selectedItem?.fileName }
        .sortedBy(RecordingEventUi::epochMs)
    val effectiveDurationMs = max(durationMs, selectedItem?.durationMs ?: 0L)
    val loadingTotalBytes = state.recordings.playbackTotalBytes
    val loadingFraction = if (loadingTotalBytes != null && loadingTotalBytes > 0L) {
        (state.recordings.playbackDownloadedBytes.toFloat() / loadingTotalBytes.toFloat()).coerceIn(0f, 1f)
    } else {
        null
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Playback",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (selectedUrl.isBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (state.recordings.playbackLoading) {
                            if (loadingFraction != null) {
                                LinearProgressIndicator(
                                    progress = loadingFraction,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp)
                                )
                                Text(
                                    text = "${(loadingFraction * 100f).toInt()}% loaded",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            } else {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp)
                                )
                            }
                            Text(
                                text = state.recordings.playbackLoadingMessage.ifBlank { "Loading video from server..." },
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center
                            )
                        } else {
                            Text("Select a recording from the list below.")
                        }
                    }
                }
            } else {
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp),
                    factory = { context ->
                        VideoView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            setOnPreparedListener { mediaPlayer ->
                                mediaPlayerRef = mediaPlayer
                                durationMs = mediaPlayer.duration.toLong().coerceAtLeast(0L)
                                if (initialSeekMs > 0L) {
                                    seekTo(initialSeekMs.coerceAtMost(durationMs).toInt())
                                    positionMs = initialSeekMs.coerceAtMost(durationMs)
                                    scrubPositionMs = positionMs
                                    initialSeekMs = 0L
                                }
                                applyPlaybackSpeed(mediaPlayer, playbackSpeed)
                                start()
                                isPlaying = true
                            }
                            setOnCompletionListener {
                                isPlaying = false
                                positionMs = durationMs
                                scrubPositionMs = durationMs
                            }
                        }
                    },
                    update = { view ->
                        videoViewRef = view
                        val playbackKey = "$selectedUrl#$selectedOffsetMs"
                        if (view.tag != playbackKey) {
                            view.tag = playbackKey
                            durationMs = 0L
                            positionMs = selectedOffsetMs
                            scrubPositionMs = selectedOffsetMs
                            initialSeekMs = selectedOffsetMs
                            view.stopPlayback()
                            view.setVideoURI(Uri.parse(selectedUrl))
                            view.requestFocus()
                            isPlaying = false
                        }
                    }
                )

                LaunchedEffect(videoViewRef, selectedUrl, scrubbing) {
                    while (videoViewRef != null && selectedUrl.isNotBlank()) {
                        if (!scrubbing) {
                            val currentPosition = videoViewRef?.currentPosition?.toLong()?.coerceAtLeast(0L) ?: 0L
                            positionMs = currentPosition
                            scrubPositionMs = currentPosition
                        }
                        isPlaying = videoViewRef?.isPlaying == true
                        delay(250L)
                    }
                }

                if (state.recordings.playbackLoading) {
                    if (loadingFraction != null) {
                        LinearProgressIndicator(
                            progress = loadingFraction,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "${(loadingFraction * 100f).toInt()}% loaded",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Text(
                        text = state.recordings.playbackLoadingMessage.ifBlank { "Loading video from server..." },
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                val sliderMax = max(effectiveDurationMs, 1L).toFloat()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ActiveActionButton(
                        text = if (isPlaying) "Playing" else "Play",
                        active = isPlaying,
                        onClick = {
                            videoViewRef?.start()
                            isPlaying = true
                        },
                        modifier = Modifier.weight(1f),
                        enabled = selectedUrl.isNotBlank()
                    )
                    ActiveActionButton(
                        text = if (isPlaying) "Pause" else "Paused",
                        active = !isPlaying,
                        onClick = {
                            videoViewRef?.pause()
                            isPlaying = false
                        },
                        modifier = Modifier.weight(1f),
                        enabled = selectedUrl.isNotBlank()
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(0.5f, 1f, 1.5f, 2f).forEach { speed ->
                        ActiveActionButton(
                            text = "${speed}x",
                            active = playbackSpeed == speed,
                            onClick = {
                                playbackSpeed = speed
                                mediaPlayerRef?.let { mediaPlayer -> applyPlaybackSpeed(mediaPlayer, speed) }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                if (selectedEvents.isNotEmpty() && effectiveDurationMs > 0L && selectedItem != null) {
                    RecordingEventMarkerStrip(
                        selectedItem = selectedItem,
                        events = selectedEvents,
                        onAction = onAction
                    )
                }
                Slider(
                    value = (if (scrubbing) scrubPositionMs else positionMs).coerceAtMost(effectiveDurationMs).toFloat(),
                    onValueChange = { value ->
                        scrubbing = true
                        scrubPositionMs = value.toLong()
                    },
                    onValueChangeFinished = {
                        val target = scrubPositionMs.coerceAtMost(effectiveDurationMs)
                        videoViewRef?.seekTo(target.toInt())
                        positionMs = target
                        scrubbing = false
                    },
                    valueRange = 0f..sliderMax
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatDuration(positionMs), style = MaterialTheme.typography.bodySmall)
                    Text(formatDuration(effectiveDurationMs), style = MaterialTheme.typography.bodySmall)
                }
            }

            if (selectedItem != null) {
                Text(
                    text = selectedItem.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                RecordingMetadataRow("Date / time", formatDateTime(selectedItem.startedAtEpochMs))
                RecordingMetadataRow("Duration", formatDuration(selectedItem.durationMs))
                RecordingMetadataRow("File size", formatSize(selectedItem.sizeBytes))
                RecordingMetadataRow(
                    "Server source",
                    state.recordings.selectedPlaybackSourceLabel.ifBlank { "LAN server" }
                )
                RecordingMetadataRow(
                    "Format",
                    "${selectedItem.container} / ${selectedItem.codec}"
                )
                if (state.recordings.playbackLoadingMessage.isNotBlank() && !state.recordings.playbackLoading) {
                    Text(
                        text = state.recordings.playbackLoadingMessage,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingMetadataRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun RecordingEventMarkerStrip(
    selectedItem: RecordingItemUi,
    events: List<RecordingEventUi>,
    onAction: (ZCamUiAction) -> Unit
) {
    val durationMs = selectedItem.durationMs.coerceAtLeast(1L).toFloat()
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
    ) {
        val barWidth = maxWidth
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .align(Alignment.Center)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
        )
        events.forEach { event ->
            val offsetMs = event.recordingOffsetMs ?: return@forEach
            val markerFraction = (offsetMs / durationMs).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(20.dp)
                    .background(MaterialTheme.colorScheme.error, RoundedCornerShape(6.dp))
                    .align(Alignment.CenterStart)
                    .offset(x = barWidth * markerFraction)
                    .clickable {
                        onAction(ZCamUiAction.PlayRecording(selectedItem.fileName, event.epochMs))
                    }
            )
        }
    }
    Text(
        text = "Current Clip Timeline. Event markers stay pinned to this clip. Tap a marker to jump to the event.",
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun RecordingTimelineCard(
    state: ZCamUiState,
    onAction: (ZCamUiAction) -> Unit
) {
    val segments = state.recordings.items.sortedBy(RecordingItemUi::startedAtEpochMs)
    val events = state.recordings.events.sortedBy(RecordingEventUi::epochMs)
    val rangeStart = segments.minOfOrNull(RecordingItemUi::startedAtEpochMs)
        ?: events.minOfOrNull(RecordingEventUi::epochMs)
    val rangeEnd = segments.maxOfOrNull(RecordingItemUi::endedAtEpochMs)
        ?: events.maxOfOrNull(RecordingEventUi::epochMs)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Recording History Timeline",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (rangeStart == null || rangeEnd == null || rangeEnd <= rangeStart) {
                Text(
                    text = "Load recordings to build the timeline.",
                    style = MaterialTheme.typography.bodySmall
                )
                return@Column
            }

            val totalRangeMs = (rangeEnd - rangeStart).coerceAtLeast(1L)
            val tickIntervalMs = historyTickIntervalMs(totalRangeMs)
            val ticks = remember(rangeStart, rangeEnd, tickIntervalMs) {
                buildHistoryTicks(rangeStart, rangeEnd, tickIntervalMs)
            }
            val selectedEpochMs = state.recordings.items
                .firstOrNull { it.fileName == state.recordings.selectedFileName }
                ?.let { item ->
                    item.startedAtEpochMs + state.recordings.selectedPlaybackOffsetMs
                        .coerceIn(0L, item.durationMs.coerceAtLeast(0L))
                }
            val scrollState = rememberScrollState()
            val coroutineScope = rememberCoroutineScope()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { coroutineScope.launch { scrollState.animateScrollTo(0) } },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Start")
                }
                OutlinedButton(
                    onClick = { coroutineScope.launch { scrollState.animateScrollTo(scrollState.maxValue) } },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Latest")
                }
            }

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(152.dp)
            ) {
                val density = LocalDensity.current
                val targetWidth = ((ticks.size.coerceAtLeast(2)) * 112).dp
                val timelineWidth = maxWidth
                    .coerceAtLeast(targetWidth)
                    .coerceAtMost(MAX_HISTORY_TIMELINE_WIDTH_DP)
                val timelineWidthPx = with(density) { timelineWidth.toPx().coerceAtLeast(1f) }
                val eventClusters = remember(events, rangeStart, rangeEnd, timelineWidthPx) {
                    clusterHistoryEvents(events, rangeStart, rangeEnd, timelineWidthPx)
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(152.dp)
                        .horizontalScroll(scrollState)
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .width(timelineWidth)
                            .height(152.dp)
                            .pointerInput(rangeStart, rangeEnd, timelineWidthPx) {
                                detectTapGestures { offset ->
                                    val fraction = (offset.x / timelineWidthPx).coerceIn(0f, 1f)
                                    val selectedEpoch = rangeStart + (totalRangeMs * fraction).toLong()
                                    onAction(ZCamUiAction.PlayRecordingAtEpoch(selectedEpoch))
                                }
                            }
                    ) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .offset(y = 72.dp)
                                .fillMaxWidth()
                                .height(2.dp)
                                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
                        )

                        segments.forEach { item ->
                            val startFraction = ((item.startedAtEpochMs - rangeStart).toFloat() / totalRangeMs).coerceIn(0f, 1f)
                            val endFraction = ((item.endedAtEpochMs - rangeStart).toFloat() / totalRangeMs).coerceIn(0f, 1f)
                            val widthFraction = (endFraction - startFraction).coerceAtLeast(0.002f)
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .offset(x = timelineWidth * startFraction, y = 56.dp)
                                    .width((timelineWidth * widthFraction).coerceAtLeast(6.dp))
                                    .height(28.dp)
                                    .background(
                                        if (state.recordings.selectedFileName == item.fileName) {
                                            MaterialTheme.colorScheme.tertiary
                                        } else {
                                            MaterialTheme.colorScheme.primaryContainer
                                        },
                                        RoundedCornerShape(6.dp)
                                    )
                                    .clickable {
                                        onAction(ZCamUiAction.PlayRecordingAtEpoch(item.startedAtEpochMs))
                                    }
                            )
                        }

                        ticks.forEach { tick ->
                            val fraction = ((tick.epochMs - rangeStart).toFloat() / totalRangeMs).coerceIn(0f, 1f)
                            val x = timelineWidth * fraction
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .offset(x = x, y = if (tick.major) 86.dp else 92.dp)
                                    .width(1.dp)
                                    .height(if (tick.major) 18.dp else 10.dp)
                                    .background(MaterialTheme.colorScheme.outline)
                            )
                            Text(
                                text = tick.label,
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .offset(x = x - 42.dp, y = 108.dp)
                                    .width(84.dp)
                            )
                        }

                        eventClusters.forEach { cluster ->
                            val fraction = ((cluster.epochMs - rangeStart).toFloat() / totalRangeMs).coerceIn(0f, 1f)
                            val markerWidth = if (cluster.count > 1) 30.dp else 14.dp
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .offset(x = timelineWidth * fraction - (markerWidth / 2f), y = 18.dp)
                                    .width(markerWidth)
                                    .height(if (cluster.count > 1) 24.dp else 34.dp)
                                    .background(
                                        MaterialTheme.colorScheme.error,
                                        RoundedCornerShape(if (cluster.count > 1) 12.dp else 7.dp)
                                    )
                                    .clickable {
                                        onAction(ZCamUiAction.PlayRecordingAtEpoch(cluster.epochMs))
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (cluster.count > 1) {
                                    Text(
                                        text = cluster.count.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onError,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        selectedEpochMs?.let { epoch ->
                            if (epoch in rangeStart..rangeEnd) {
                                val fraction = ((epoch - rangeStart).toFloat() / totalRangeMs).coerceIn(0f, 1f)
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .offset(x = timelineWidth * fraction)
                                        .width(2.dp)
                                        .height(132.dp)
                                        .background(MaterialTheme.colorScheme.secondary)
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(formatDateTime(rangeStart), style = MaterialTheme.typography.bodySmall)
                Text(formatDateTime(rangeEnd), style = MaterialTheme.typography.bodySmall)
            }
            Text(
                text = selectedEpochMs?.let { "Selected ${formatDateTime(it)}. Drag the timeline horizontally; tap any recorded block or event to seek." }
                    ?: "Drag the timeline horizontally. Gaps are real missing recording time; tapping a gap reports no recording.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun RecordingSegmentsList(
    state: ZCamUiState,
    onAction: (ZCamUiAction) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Segments",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (state.recordings.items.isEmpty()) {
                Text("No recordings in the selected range.", style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            state.recordings.items.forEach { item ->
                val downloadActive = state.recordings.downloadLoading &&
                    state.recordings.activeDownloadFileName == item.fileName
                val downloadFraction = if (downloadActive) {
                    val totalBytes = state.recordings.downloadTotalBytes
                    if (totalBytes != null && totalBytes > 0L) {
                        (state.recordings.downloadDownloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                    } else {
                        null
                    }
                } else {
                    null
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (state.recordings.selectedFileName == item.fileName) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(item.fileName, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "Date ${formatDateTime(item.startedAtEpochMs)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "Duration ${formatDuration(item.durationMs)}  Size ${formatSize(item.sizeBytes)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "Format ${item.container} / ${item.codec}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (downloadActive) {
                            if (downloadFraction != null) {
                                LinearProgressIndicator(
                                    progress = downloadFraction,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { onAction(ZCamUiAction.PlayRecording(item.fileName)) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Play")
                            }
                            Button(
                                onClick = { onAction(ZCamUiAction.DownloadRecording(item.fileName)) },
                                modifier = Modifier.weight(1f),
                                enabled = !state.recordings.downloadLoading || downloadActive
                            ) {
                                Text(
                                    if (downloadActive) {
                                        downloadFraction?.let { fraction -> "Download ${(fraction * 100f).toInt()}%" }
                                            ?: "Downloading..."
                                    } else {
                                        "Download"
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class HistoryTickUi(
    val epochMs: Long,
    val label: String,
    val major: Boolean
)

private data class HistoryEventClusterUi(
    val epochMs: Long,
    val count: Int
)

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS
private const val EVENT_CLUSTER_DISTANCE_PX = 26f
private val MAX_HISTORY_TIMELINE_WIDTH_DP: Dp = 9_600.dp

private fun historyTickIntervalMs(rangeMs: Long): Long {
    return when {
        rangeMs < 90 * MINUTE_MS -> 15 * MINUTE_MS
        rangeMs < 2 * HOUR_MS -> 30 * MINUTE_MS
        rangeMs <= 8 * HOUR_MS -> HOUR_MS
        rangeMs <= 16 * HOUR_MS -> 2 * HOUR_MS
        rangeMs <= DAY_MS -> 4 * HOUR_MS
        rangeMs <= 3 * DAY_MS -> 6 * HOUR_MS
        else -> 12 * HOUR_MS
    }
}

private fun buildHistoryTicks(
    rangeStart: Long,
    rangeEnd: Long,
    intervalMs: Long
): List<HistoryTickUi> {
    val rangeMs = (rangeEnd - rangeStart).coerceAtLeast(1L)
    val ticks = mutableListOf<HistoryTickUi>()
    var tick = (rangeStart / intervalMs) * intervalMs
    if (tick < rangeStart) tick += intervalMs
    while (tick <= rangeEnd) {
        ticks += HistoryTickUi(
            epochMs = tick,
            label = formatHistoryTick(tick, rangeMs),
            major = isMajorHistoryTick(tick, intervalMs, rangeMs)
        )
        tick += intervalMs
    }
    if (ticks.isEmpty()) {
        ticks += HistoryTickUi(rangeStart, formatHistoryTick(rangeStart, rangeMs), major = true)
        ticks += HistoryTickUi(rangeEnd, formatHistoryTick(rangeEnd, rangeMs), major = true)
    }
    return ticks
}

private fun clusterHistoryEvents(
    events: List<RecordingEventUi>,
    rangeStart: Long,
    rangeEnd: Long,
    timelineWidthPx: Float
): List<HistoryEventClusterUi> {
    val totalRangeMs = (rangeEnd - rangeStart).coerceAtLeast(1L).toFloat()
    val positioned = events
        .filter { it.epochMs in rangeStart..rangeEnd }
        .map { event ->
            val x = (((event.epochMs - rangeStart).toFloat() / totalRangeMs).coerceIn(0f, 1f)) * timelineWidthPx
            event to x
        }
        .sortedBy { it.second }
    if (positioned.isEmpty()) return emptyList()

    val clusters = mutableListOf<HistoryEventClusterUi>()
    var currentEvents = mutableListOf<RecordingEventUi>()
    var currentLastX = positioned.first().second

    fun flush() {
        if (currentEvents.isEmpty()) return
        val representative = currentEvents.maxWithOrNull(
            compareBy<RecordingEventUi> { it.confidencePercent }.thenByDescending { it.epochMs }
        ) ?: currentEvents.first()
        clusters += HistoryEventClusterUi(
            epochMs = representative.epochMs,
            count = currentEvents.size
        )
        currentEvents = mutableListOf()
    }

    positioned.forEach { (event, x) ->
        if (currentEvents.isNotEmpty() && x - currentLastX > EVENT_CLUSTER_DISTANCE_PX) {
            flush()
        }
        currentEvents += event
        currentLastX = x
    }
    flush()
    return clusters
}

private fun formatHistoryTick(epochMs: Long, rangeMs: Long): String {
    val pattern = if (rangeMs > DAY_MS) "MM-dd HH:mm" else "HH:mm"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(epochMs))
}

private fun isMajorHistoryTick(
    epochMs: Long,
    intervalMs: Long,
    rangeMs: Long
): Boolean {
    return when {
        rangeMs > DAY_MS -> epochMs % DAY_MS == 0L
        intervalMs < HOUR_MS -> epochMs % HOUR_MS == 0L
        else -> true
    }
}

private fun formatDateTime(epochMs: Long): String {
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        .format(Date(epochMs))
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

private fun formatSize(sizeBytes: Long): String {
    val safeBytes = sizeBytes.coerceAtLeast(0L).toDouble()
    val kib = 1024.0
    val mib = kib * 1024.0
    val gib = mib * 1024.0
    return when {
        safeBytes >= gib -> String.format("%.2f GB", safeBytes / gib)
        safeBytes >= mib -> String.format("%.1f MB", safeBytes / mib)
        safeBytes >= kib -> String.format("%.1f KB", safeBytes / kib)
        else -> "${safeBytes.toLong()} B"
    }
}

@Composable
private fun resultToneColor(tone: StatusTone): ComposeColor {
    return when (tone) {
        StatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
        StatusTone.HEALTHY -> ComposeColor(0xFF1F7A3D)
        StatusTone.WARNING -> MaterialTheme.colorScheme.onSecondaryContainer
        StatusTone.ERROR -> MaterialTheme.colorScheme.error
    }
}

private fun applyPlaybackSpeed(
    mediaPlayer: android.media.MediaPlayer,
    speed: Float
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    runCatching {
        mediaPlayer.playbackParams = mediaPlayer.playbackParams.setSpeed(speed)
    }
}
