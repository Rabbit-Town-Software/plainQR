package com.rabbittownsoftware.plainqr

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URI
import java.util.concurrent.Executors


/**
 * MainActivity is the entry point of the PlainQR app.
 * It shows an animated splash screen before launching the camera-based QR scanner UI.
 *
 * The splash screen fades in/out smoothly and displays the Rabbit Town Software logo.
 * Once finished, the PlainQRApp composable handles camera permission, scanning, and UI.
 */
class MainActivity : ComponentActivity()
{
    /**
     * Called when the activity is first created.
     *
     * Sets the app content using Jetpack Compose. Renders a splash screen
     * followed by the main QR scanning interface.
     *
     * @param savedInstanceState Optional saved instance state.
     */
    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)
        setContent()
        {
            var splashVisible by remember { mutableStateOf(true) }
            var showMain by remember { mutableStateOf(false) }

            // Splash screen timing and animation control
            LaunchedEffect(Unit)
            {
                delay(300)   // brief buffer before fade-in
                splashVisible = true
                delay(1200)  // show splash
                splashVisible = false
                delay(400)   // allow fade-out
                showMain = true
            }

            // Root container for splash and app
            Box(modifier = Modifier.fillMaxSize())
            {
                // Splash Screen Layer
                AnimatedVisibility(
                    visible = splashVisible,
                    enter = fadeIn(tween(600)),
                    exit = fadeOut(tween(400))
                )
                {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.White),
                        contentAlignment = Alignment.Center
                    )
                    {
                        Image(
                            painter = painterResource(id = R.drawable.splash_logo),
                            contentDescription = "Rabbit Town Splash",
                            modifier = Modifier.size(700.dp)
                        )
                    }
                }

                // Main QR Scanner UI Layer
                if (showMain)
                {
                    PlainQRApp()
                }
            }
        }
    }
}

/**
 * PlainQRApp is the core UI for the QR scanning experience.
 *
 * Handles camera permission, sets up CameraX with a live preview and ML Kit barcode scanning,
 * and shows a result dialog with options to visit the URL or resume scanning.
 */
@OptIn(ExperimentalGetImage::class)
@Composable
fun PlainQRApp()
{
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    var hasPermission by remember { mutableStateOf(false) }
    var qrResult by remember { mutableStateOf<String?>(null) }
    var showDialog by remember { mutableStateOf(false) }
    var isCameraActive by remember { mutableStateOf(true) }

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val lifecycle = lifecycleOwner.lifecycle

    // Resume camera when returning from browser if needed
    DisposableEffect(lifecycle)
    {
        val observer = LifecycleEventObserver()
        {
            _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !isCameraActive && !showDialog)
            {
                isCameraActive = true
            }
        }
        lifecycle.addObserver(observer)

        onDispose()
        {
            lifecycle.removeObserver(observer)
        }
    }

    // Request camera permission
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())
    {
            granted -> hasPermission = granted
    }

    LaunchedEffect(Unit)
    {
        launcher.launch(Manifest.permission.CAMERA)
    }

    // Main layout box
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF181818))
    )
    {
        // Camera preview and analyzer setup
        if (hasPermission && isCameraActive)
        {
            AndroidView(
                factory =
                {
                    ctx -> val previewView = PreviewView(ctx)

                    val preview = Preview.Builder().build().also()
                    {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                    val imageAnalyzer = ImageAnalysis.Builder().build().also()
                    {
                        analysis -> analysis.setAnalyzer(cameraExecutor)
                        {
                            imageProxy -> val mediaImage = imageProxy.image
                            if (mediaImage != null)
                            {
                                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                                val buffer = mediaImage.planes[0].buffer
                                val data = ByteArray(buffer.remaining())
                                buffer.get(data)

                                val width = mediaImage.width
                                val height = mediaImage.height

                                try
                                {
                                    val source = PlanarYUVLuminanceSource(
                                        data,
                                        width,
                                        height,
                                        0,
                                        0,
                                        width,
                                        height,
                                        false
                                    )
                                    val bitmap = BinaryBitmap(HybridBinarizer(source))
                                    val reader = MultiFormatReader()
                                    val result = reader.decode(bitmap)

                                    if (isValidUrl(result.text) && !showDialog)
                                    {
                                        qrResult = result.text
                                        showDialog = true
                                        isCameraActive = false
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                }
                                catch (_: Exception)
                                {
                                    // Failed to decode — do nothing
                                }
                                finally
                                {
                                    imageProxy.close()
                                }
                            }
                            else
                            {
                                imageProxy.close()
                            }
                        }
                    }

                    cameraProviderFuture.get().apply()
                    {
                        unbindAll()
                        bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalyzer
                        )
                    }

                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Dialog shown after QR is scanned
        AnimatedVisibility(
            visible = showDialog && qrResult != null,
            enter = fadeIn(),
            exit = fadeOut()
        )
        {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xAA000000))
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            )
            {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF2C2C2C), RoundedCornerShape(12.dp))
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                )
                {
                    Text(
                        text = "Scanned site:",
                        color = Color.White,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = formatHost(qrResult ?: ""),
                        color = Color(0xFF40C4FF),
                        fontSize = 20.sp,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    {
                        AnimatedActionButton(
                            label = "Visit",
                            modifier = Modifier.weight(1f)
                        )
                        {
                            val intent = Intent(Intent.ACTION_VIEW).apply()
                            {
                                data = qrResult?.toUri()
                            }
                            context.startActivity(intent)
                            showDialog = false
                        }

                        AnimatedActionButton(
                            label = "Exit",
                            modifier = Modifier.weight(1f)
                        )
                        {
                            qrResult = null
                            showDialog = false
                            isCameraActive = true
                        }
                    }
                }
            }
        }

        // Fallback for denied permission
        if (!hasPermission)
        {
            Text(
                text = "Camera permission required",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

/**
 * Checks whether a given string is a valid HTTP or HTTPS URL.
 *
 * A valid URL must have a scheme (http or https) and a host.
 *
 * @param url The string to validate.
 * @return True if the string is a valid URL, false otherwise.
 */
fun isValidUrl(url: String): Boolean
{
    return try
    {
        val uri = URI(url)
        uri.scheme?.startsWith("http") == true && uri.host != null
    }
    catch (_: Exception)
    {
        false
    }
}

/**
 * Extracts and returns the host (domain) from a full URL.
 *
 * If parsing fails or the host is null, it returns the original URL string.
 *
 * @param url The full URL string to parse.
 * @return The host portion (e.g., "example.com"), or the original input on failure.
 */
fun formatHost(url: String): String
{
    return try
    {
        URI(url).host ?: url
    }
    catch (_: Exception)
    {
        url
    }
}

/**
 * AnimatedActionButton is a reusable button composable with:
 * - A glowing animated RGB border
 * - Tap scaling animation
 * - Press cooldown to prevent spamming
 * - Optional haptic feedback on tap
 *
 * @param label The text to display on the button.
 * @param modifier Optional modifier to customize layout or styling.
 * @param onClick Callback invoked when the button is pressed.
 */
@Composable
fun AnimatedActionButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
)
{
    val haptics = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    var isCooldown by remember { mutableStateOf(false) }

    // Looping gradient animation for glowing border
    val infiniteTransition = rememberInfiniteTransition()
    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    val baseColors = listOf(
        Color(0xFFBB86FC), Color(0xFF8C9EFF), Color(0xFF64B5F6),
        Color(0xFF40C4FF), Color(0xFF80D8FF), Color(0xFFBB86FC)
    )

    // Precomputed gradient stops for smooth animation
    val expandedStops = buildList()
    {
        val steps = 60
        for (i in 0 until steps)
        {
            val t = i / steps.toFloat()
            val index = (t * (baseColors.size - 1)).toInt()
            val blend = t * (baseColors.size - 1) % 1f
            val color = lerp(
                baseColors[index],
                baseColors.getOrElse(index + 1) { baseColors.last() },
                blend
            )
            add((t + progress) % 1f to color)
        }
    }.sortedBy { it.first }

    var pressed by remember { mutableStateOf(false) }

    // Scale effect on press
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "scale"
    )

    // Button container with animated border
    Box(
        modifier = modifier.height(56.dp).graphicsLayer
            {
                scaleX = scale
                scaleY = scale
            }.padding(4.dp),
        contentAlignment = Alignment.Center
    )
    {
        // Draw glowing animated border
        Canvas(modifier = Modifier.fillMaxSize().padding(2.dp))
        {
            val stroke = 2.dp.toPx()
            val radius = 16.dp.toPx()

            drawRoundRect(
                brush = Brush.sweepGradient(*expandedStops.toTypedArray()),
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(size.width - stroke, size.height - stroke),
                cornerRadius = CornerRadius(radius),
                style = Stroke(width = stroke)
            )
        }

        // Actual button
        Button(
            onClick =
                {
                    if (!isCooldown)
                    {
                        isCooldown = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onClick()
                        coroutineScope.launch()
                        {
                            delay(200)
                            isCooldown = false
                        }
                    }
                },
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2C)),
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp)
        )
        {
            Text(
                text = label,
                fontSize = 20.sp,
                color = Color.White,
                fontFamily = FontFamily.Default
            )
        }
    }
}
