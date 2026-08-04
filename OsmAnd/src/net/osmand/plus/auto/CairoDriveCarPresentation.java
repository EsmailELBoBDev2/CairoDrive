package net.osmand.plus.auto;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Looper;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.core.android.AtlasMapRendererView;
import net.osmand.core.android.MapRendererContext;
import net.osmand.core.android.MapRendererView;
import net.osmand.core.jni.ZoomLevel;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.cairodrive.CairoDriveLogger;
import net.osmand.plus.views.OsmAndMapLayersView;
import net.osmand.plus.views.OsmandMapTileView;
import net.osmand.plus.views.corenative.NativeCoreContext;

/**
 * Draws the Android Auto map through a {@link VirtualDisplay} backed by the head unit's own
 * {@link Surface}, instead of rendering offscreen and copying the result across every frame.
 *
 * <h3>Why</h3>
 *
 * The existing path in {@link SurfaceRenderer} does three things per frame: render the map into an
 * offscreen GL framebuffer, read those pixels back into a {@link android.graphics.Bitmap}, and blit
 * that bitmap onto a canvas obtained from {@code surface.lockCanvas()}. The overlays - route line,
 * position arrow, widgets - are then drawn onto that same canvas. The 2026-08-04 drive measured the
 * cost of that, over 32,200 frames with the head unit connected:
 *
 * <pre>
 *   over 25.9ms (61%) | blit 9.2 (22%) | read 4.5 (11%) | lock 1.6 | post 1.0 | wdgt 0.1
 *   avgMs 46.9, maxMs 243, 13.1% slow frames
 * </pre>
 *
 * A {@code lockCanvas()} canvas is never hardware accelerated - that is AOSP's documented
 * behaviour. So {@code blit} runs on the CPU through Skia, and so does every overlay in
 * {@code over}. Together that is 35.1 ms of a 46.9 ms frame spent on the CPU doing work a GPU is
 * built for.
 *
 * <h3>What this does instead</h3>
 *
 * It builds, on a display whose output IS the car surface, exactly the view stack the phone already
 * uses - {@code MapViewWithLayers}: an {@link AtlasMapRendererView} for the GL map, and an
 * {@link OsmAndMapLayersView} on top for the overlays. Both then render through the ordinary
 * hardware-accelerated Android view pipeline straight into the head unit's buffer.
 *
 * <p>All three copies disappear at once, because none of them is a step in that pipeline:
 * <ul>
 *   <li>no {@code read} - nothing is pulled out of the GPU;</li>
 *   <li>no {@code blit} - nothing is copied onto a second canvas;</li>
 *   <li>{@code over} moves onto the GPU - {@link OsmAndMapLayersView} is a plain {@link View}, and a
 *       View's canvas on a hardware-accelerated window records into a display list.</li>
 * </ul>
 *
 * <p>{@code CAIRODRIVE_SURFACE_OVERSCAN} and {@code CAIRODRIVE_RENDER_SCALE} both become irrelevant
 * on this path and are deliberately not applied: each exists only to reduce the number of pixels
 * moved by the readback and the blit, and there is no readback and no blit.
 *
 * <h3>Permissions</h3>
 *
 * None are needed. The virtual display is created with {@code VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY}
 * and is therefore private, so the window {@link Presentation} adds is typed
 * {@code TYPE_PRIVATE_PRESENTATION}, which {@code PhoneWindowManager.checkAddPermission} returns
 * {@code ADD_OKAY} for without {@code SYSTEM_ALERT_WINDOW}. Google documents the VirtualDisplay +
 * Presentation pair as the way to draw a map on a car head unit, and Organic Maps ships it.
 *
 * <h3>Failure is expected to be survivable, not impossible</h3>
 *
 * This has never run on this head unit. Every step is inside a try/catch, {@link #attach} returns
 * false on any failure with {@link #getFailureReason()} set, and {@link SurfaceRenderer} then keeps
 * the offscreen-and-blit path exactly as it is today. The worst case is the frame cost this build
 * already has, plus one line in the log naming the step that failed - not a black screen.
 */
public class CairoDriveCarPresentation {

	private static final String DISPLAY_NAME = "CairoDrive-AA";

	@Nullable
	private DisplayManager displayManager;
	@Nullable
	private VirtualDisplay virtualDisplay;
	@Nullable
	private Presentation presentation;
	@Nullable
	private AtlasMapRendererView rendererView;
	@Nullable
	private OsmAndMapLayersView layersView;
	@Nullable
	private OsmandMapTileView mapView;

	@Nullable
	private String failureReason;

	public boolean isActive() {
		return presentation != null && rendererView != null;
	}

	@Nullable
	public String getFailureReason() {
		return failureReason;
	}

	/**
	 * Builds the virtual display, the presentation and the view stack, and hands the map over to it.
	 *
	 * <p>Must be called on the main thread: {@link Presentation} is a {@link android.app.Dialog}, so
	 * it needs a thread with a prepared {@link Looper} and adds a window through the ordinary
	 * WindowManager path.
	 *
	 * @return true when the presentation is showing and owns the map; false when nothing was
	 * changed and the caller should keep its existing renderer. On false, every resource this
	 * method created has already been released.
	 */
	public boolean attach(@NonNull OsmandApplication app,
	                      @NonNull Context uiContext,
	                      @NonNull Surface surface,
	                      int width, int height, int densityDpi,
	                      @NonNull OsmandMapTileView mapView,
	                      int maximumFrameRate,
	                      boolean enableMSAA,
	                      boolean sphericalMap,
	                      float elevationAngle,
	                      int minZoom, int maxZoom,
	                      int symbolsUpdateInterval) {
		if (Looper.myLooper() != Looper.getMainLooper()) {
			return fail("attach called off the main thread");
		}
		if (width <= 0 || height <= 0) {
			return fail("surface has no size yet (" + width + "x" + height + ")");
		}
		if (!surface.isValid()) {
			return fail("surface is not valid");
		}
		try {
			displayManager = (DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
			if (displayManager == null) {
				return fail("no DisplayManager");
			}
			// OWN_CONTENT_ONLY keeps the display private, which is what makes the presentation
			// window type TYPE_PRIVATE_PRESENTATION and therefore permission-free. PRESENTATION
			// marks it as a display a Presentation may be shown on at all.
			int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
					| DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
			virtualDisplay = displayManager.createVirtualDisplay(DISPLAY_NAME, width, height,
					densityDpi > 0 ? densityDpi : 160, surface, flags);
			if (virtualDisplay == null || virtualDisplay.getDisplay() == null) {
				return fail("createVirtualDisplay returned null");
			}

			MapRendererView[] previousHolder = new MapRendererView[1];
			MapRendererContext rendererContext = handOverRenderer(app, mapView, densityDpi, previousHolder);
			if (rendererContext == null) {
				return fail("no MapRendererContext - the GL core is not initialised");
			}

			presentation = new Presentation(uiContext, virtualDisplay.getDisplay());
			Window window = presentation.getWindow();
			if (window == null) {
				return fail("presentation has no window");
			}
			window.requestFeature(Window.FEATURE_NO_TITLE);
			// The map is opaque and covers the whole display, but a black background means that a
			// frame drawn before the first GL frame lands reads as a dark map rather than as
			// whatever was left in the buffer.
			window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
			window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

			FrameLayout root = new FrameLayout(presentation.getContext());
			rendererView = new AtlasMapRendererView(presentation.getContext());
			layersView = new TimedLayersView(presentation.getContext(), width, height, densityDpi,
					maximumFrameRate);
			root.addView(rendererView, new FrameLayout.LayoutParams(
					LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
			root.addView(layersView, new FrameLayout.LayoutParams(
					LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
			presentation.setContentView(root);
			presentation.show();

			rendererContext.presetMapRendererOptions(rendererView, enableMSAA);
			// 0,0 is WINDOW mode - the core sizes itself from the view it is attached to. The car
			// path passes explicit dimensions today precisely because it renders offscreen; here
			// the view has a real window on the virtual display, so this is the phone's own call.
			// The previous renderer view is handed in, not dropped: both the phone path and the car
			// path pass it so the core can carry its state across instead of rebuilding it, and
			// dropping it here would leave the old one holding GL resources.
			rendererView.setupRenderer(presentation.getContext(), 0, 0, previousHolder[0]);
			rendererView.setMinZoomLevel(ZoomLevel.swigToEnum(minZoom));
			rendererView.setMaxZoomLevel(ZoomLevel.swigToEnum(maxZoom));
			rendererView.setAzimuth(0);
			rendererView.setFlatEarth(!sphericalMap);
			rendererView.removeAllSymbolsProviders();
			rendererView.resumeSymbolsUpdate();
			rendererView.setSymbolsUpdateInterval(symbolsUpdateInterval);
			// Higher than the offscreen path's cap. That cap was set against a 46.9 ms frame, where
			// asking for more frames only queued work that could not be delivered. With the readback
			// and the blit gone the budget is a different size, and the head unit's own video path
			// runs at 30.
			rendererView.setMaximumFrameRate(maximumFrameRate);
			rendererView.setElevationAngle(elevationAngle);

			rendererContext.setMapRendererView(rendererView);
			mapView.setMapRenderer(rendererView, false);
			mapView.setElevationAngle(elevationAngle);
			// The overlays now draw into a hardware-accelerated View canvas rather than into the
			// software canvas from lockCanvas(). This single line is what moves `over`.
			layersView.setMapView(mapView);
			this.mapView = mapView;

			failureReason = null;
			CairoDriveLogger.getInstance().log("CD_PRESENT",
					"attached display=" + width + "x" + height + " dpi=" + densityDpi
							+ " maxFps=" + maximumFrameRate + " msaa=" + enableMSAA);
			return true;
		} catch (Throwable t) {
			// Throwable, not Exception: a missing or differently-shaped method on the prebuilt
			// OsmAndCore binding surfaces as an Error, and that must fall back like anything else
			// rather than take the navigation session down.
			return fail(t.getClass().getSimpleName() + ": " + t.getMessage());
		}
	}

	/**
	 * Takes the renderer away from whatever currently holds it, the same handover
	 * {@code MapViewWithLayers.setupAtlasMapRendererView} performs when the phone reclaims the map.
	 */
	@Nullable
	private MapRendererContext handOverRenderer(@NonNull OsmandApplication app,
	                                            @NonNull OsmandMapTileView mapView,
	                                            int densityDpi,
	                                            @NonNull MapRendererView[] previousHolder) {
		MapRendererContext rendererContext = NativeCoreContext.getMapRendererContext();
		if (rendererContext == null) {
			return null;
		}
		if (mapView.getMapRenderer() != null) {
			mapView.detachMapRenderer();
		}
		MapRendererView previous = rendererContext.getMapRendererView();
		if (previous != null) {
			previousHolder[0] = previous;
			rendererContext.setMapRendererView(null);
		}
		NativeCoreContext.setMapRendererContext(app, densityDpi > 0 ? densityDpi / 160f : 1f);
		return NativeCoreContext.getMapRendererContext();
	}

	/**
	 * Asks the overlay view to redraw. The GL map drives itself from the core's own frame loop, so
	 * only the overlay layer needs poking when the app decides something changed.
	 */
	public void invalidateOverlays() {
		OsmAndMapLayersView view = layersView;
		if (view != null) {
			// postInvalidateOnAnimation, not invalidate: this is reached from the map's own render
			// and location threads, and View.invalidate() is main-thread only. It also coalesces,
			// so several calls inside one frame cost one redraw rather than several.
			view.postInvalidateOnAnimation();
		}
	}

	public void onResume() {
		AtlasMapRendererView view = rendererView;
		if (view != null) {
			try {
				view.handleOnResume();
			} catch (Throwable ignored) {
			}
		}
	}

	public void onPause() {
		AtlasMapRendererView view = rendererView;
		if (view != null) {
			try {
				view.handleOnPause();
			} catch (Throwable ignored) {
			}
		}
	}

	/**
	 * Releases everything, in the reverse of the order it was created. Safe to call at any point,
	 * including part-way through a failed {@link #attach}.
	 */
	public void detach() {
		OsmandMapTileView view = mapView;
		if (view != null && layersView != null) {
			try {
				layersView.setMapView(null);
			} catch (Throwable ignored) {
			}
		}
		if (view != null) {
			try {
				view.setMapRenderer(null, true);
			} catch (Throwable ignored) {
			}
		}
		if (rendererView != null) {
			try {
				MapRendererContext rendererContext = NativeCoreContext.getMapRendererContext();
				if (rendererContext != null) {
					rendererContext.releaseMapRendererView(rendererView);
				}
				rendererView.handleOnDestroy();
			} catch (Throwable ignored) {
			}
		}
		if (presentation != null) {
			try {
				presentation.dismiss();
			} catch (Throwable ignored) {
			}
		}
		if (virtualDisplay != null) {
			try {
				virtualDisplay.release();
			} catch (Throwable ignored) {
			}
		}
		mapView = null;
		layersView = null;
		rendererView = null;
		presentation = null;
		virtualDisplay = null;
		displayManager = null;
	}

	/**
	 * The overlay view, with the same frame accounting {@link SurfaceRenderer} does on the legacy
	 * path - otherwise switching to this path would silence {@code CD_FRAME} entirely and the drive
	 * that was meant to prove the change would produce no numbers to prove it with.
	 *
	 * <p>Only two of the six buckets survive the move and they are the two that matter:
	 * <ul>
	 *   <li>{@code avgOver} - how long the overlays take to draw, now on a hardware-accelerated
	 *       View canvas. Directly comparable to the 25.9 ms measured on 2026-08-04.</li>
	 *   <li>{@code avgMs} - the interval between consecutive draws, i.e. what the map actually
	 *       delivers. Comparable to the 46.9 ms / 21.3 fps measured then.</li>
	 * </ul>
	 * {@code lock}, {@code read}, {@code blit} and {@code post} are structurally absent here rather
	 * than merely small, so they are reported as a single {@code noBlit} marker instead of six
	 * zeroes that would read as "not measured".
	 */
	private static class TimedLayersView extends OsmAndMapLayersView {

		private static final int FRAMES_PER_SUMMARY = 200;
		/** At a 30 fps cap a frame has ~33 ms; past that one was dropped. */
		private static final long SLOW_FRAME_MS = 33;

		private final int surfaceWidth;
		private final int surfaceHeight;
		private final int dpi;
		private final int maxFps;

		private int frames;
		private long drawSumMs;
		private long intervalSumMs;
		private long intervalMaxMs;
		private long drawMaxMs;
		private int slowFrames;
		private long lastDrawStartMs;

		TimedLayersView(@NonNull Context context, int width, int height, int dpi, int maxFps) {
			super(context);
			this.surfaceWidth = width;
			this.surfaceHeight = height;
			this.dpi = dpi;
			this.maxFps = maxFps;
		}

		@Override
		protected void onDraw(android.graphics.Canvas canvas) {
			long startMs = System.currentTimeMillis();
			long startNanos = System.nanoTime();
			try {
				super.onDraw(canvas);
			} finally {
				long drawMs = (System.nanoTime() - startNanos) / 1_000_000L;
				record(startMs, drawMs, canvas.isHardwareAccelerated());
			}
		}

		private void record(long startMs, long drawMs, boolean hardware) {
			long intervalMs = lastDrawStartMs == 0 ? 0 : startMs - lastDrawStartMs;
			lastDrawStartMs = startMs;
			if (intervalMs <= 0) {
				// First draw of the session, or the clock went backwards. Counting it would put a
				// zero into an average whose whole purpose is to be compared against 46.9.
				return;
			}
			frames++;
			drawSumMs += drawMs;
			intervalSumMs += intervalMs;
			if (drawMs > drawMaxMs) {
				drawMaxMs = drawMs;
			}
			if (intervalMs > intervalMaxMs) {
				intervalMaxMs = intervalMs;
			}
			if (intervalMs >= SLOW_FRAME_MS) {
				slowFrames++;
			}
			if (frames >= FRAMES_PER_SUMMARY) {
				CairoDriveLogger.getInstance().log("CD_FRAME", "summary frames=" + frames
						+ " avgMs=" + (intervalSumMs / frames)
						+ " maxMs=" + intervalMaxMs + " slow=" + slowFrames
						+ " renderMode=presentation noBlit=1"
						// Reported so a claim that the overlays moved onto the GPU is checked
						// rather than assumed. A View on a hardware-accelerated window should say
						// true; false would mean the window fell back to software and `over` is
						// still being paid on the CPU, which changes what the numbers mean.
						+ " hwAccel=" + hardware
						+ " surface=" + surfaceWidth + "x" + surfaceHeight
						+ " dpi=" + dpi + " maxFps=" + maxFps
						+ " avgOver=" + (drawSumMs / frames)
						+ " maxOver=" + drawMaxMs);
				frames = 0;
				drawSumMs = 0;
				intervalSumMs = 0;
				intervalMaxMs = 0;
				drawMaxMs = 0;
				slowFrames = 0;
			}
		}
	}

	private boolean fail(@NonNull String reason) {
		failureReason = reason;
		CairoDriveLogger.getInstance().log("CD_PRESENT", "FAILED " + reason
				+ " - falling back to offscreen render + blit");
		detach();
		return false;
	}
}
