package net.osmand.plus.auto;

import static net.osmand.plus.views.OsmandMapTileView.DEFAULT_ELEVATION_ANGLE;
import static net.osmand.plus.views.MapViewWithLayers.SYMBOLS_UPDATE_INTERVAL;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.car.app.AppManager;
import androidx.car.app.CarContext;
import androidx.car.app.HostException;
import androidx.car.app.SurfaceCallback;
import androidx.car.app.SurfaceContainer;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import net.osmand.Location;
import net.osmand.core.android.AtlasMapRendererView;
import net.osmand.core.android.MapRendererContext;
import net.osmand.core.android.MapRendererView;
import net.osmand.core.android.MapRendererView.MapRendererViewListener;
import net.osmand.core.jni.ZoomLevel;
import net.osmand.data.RotatedTileBox;
import net.osmand.plus.AppInitEvents;
import net.osmand.plus.AppInitializeListener;
import net.osmand.plus.BuildConfig;
import net.osmand.plus.AppInitializer;
import net.osmand.plus.OsmAndConstants;
import net.osmand.plus.cairodrive.CairoDriveLogger;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.auto.views.CarSurfaceView;
import net.osmand.plus.helpers.MapDisplayPositionManager;
import net.osmand.plus.plugins.PluginsHelper;
import net.osmand.plus.views.OsmandMapTileView;
import net.osmand.plus.views.OsmandMapTileView.ElevationListener;
import net.osmand.plus.views.corenative.NativeCoreContext;
import net.osmand.plus.views.layers.base.OsmandMapLayer.DrawSettings;

/**
 * A very simple implementation of a renderer for the app's background surface.
 */
public final class SurfaceRenderer implements DefaultLifecycleObserver, MapRendererViewListener, ElevationListener {
	private static final String TAG = "SurfaceRenderer";

	public static final float MIN_ALLOWED_ELEVATION_ANGLE_AA = 20;

	private static final double VISIBLE_AREA_Y_MIN_DETECTION_SIZE = 1.025;
	private static final int MAP_RENDER_MESSAGE = OsmAndConstants.UI_HANDLER_MAP_VIEW + 7;
	private static final int MAX_FRAME_RATE = 20;
	/** Fill shown while the offscreen renderer is not ready - matches OsmAnd's map background. */
	private static final int EMPTY_FRAME_DAY_COLOR = 0xFFF1EEE8;
	private static final int EMPTY_FRAME_NIGHT_COLOR = 0xFF1B1B1B;
	public static final int PINCH_TO_ZOOM_ITERATION_DELAY = 200;

	private final CarContext carContext;
	private final CarSurfaceView surfaceView;
	private OsmandMapTileView mapView;
	private final Handler handler;

	@Nullable
	private AtlasMapRendererView offscreenMapRendererView;
	@Nullable
	private Surface surface;
	@Nullable
	private SurfaceContainer surfaceContainer;

	@Nullable
	private Rect visibleArea;
	@Nullable
	private Rect stableArea;

	private float cachedRatioY = 0f;
	private float cachedRatioX = 0f;
	private float cachedDefaultRatioY = 0f;
	private Rect cachedVisibleArea;

	private boolean darkMode;
	/**
	 * Whether the offscreen renderer has produced at least one real frame since it was set up.
	 * <p>
	 * getBitmap() starts returning a non-null but EMPTY bitmap as soon as the renderer object
	 * exists, well before the GL core has drawn anything into it - so blitting it paints a
	 * blank white screen that then fills in with streets as tiles arrive. That is the
	 * "map does not load for a bit" at start and after every head-unit reconnect. Waiting for
	 * onFrameReady means the first thing shown is the map's own background colour instead of
	 * white, and the map appears when it is actually a map.
	 */
	private volatile boolean firstFrameReady;

	private SurfaceRendererCallback callback;

	/**
	 * Extra offscreen width, as a fraction of the head unit's screen. See
	 * CAIRODRIVE_SURFACE_OVERSCAN in cairodrive.gradle for why this defaults to 0 here and 0.5
	 * upstream: at 0.5 the per-frame GPU readback and canvas blit each move 50% more pixels than
	 * are ever displayed, and those two copies are the bulk of an Android Auto frame.
	 */
	private static final float surfaceWidthMultiply = BuildConfig.CAIRODRIVE_SURFACE_OVERSCAN;

	/**
	 * Fraction of the head unit's resolution the map is rendered at - see CAIRODRIVE_RENDER_SCALE
	 * in cairodrive.gradle. 1.0 is native size and the default.
	 * <p>
	 * Below 1.0 the offscreen renderer produces a smaller bitmap which is stretched onto the car
	 * canvas. Both of the expensive per-frame steps - the GPU readback and the software blit -
	 * scale with pixel count, so 0.75 moves 44% fewer pixels through each. It costs sharpness,
	 * which is why the default changes nothing.
	 */
	private static final float renderScale = BuildConfig.CAIRODRIVE_RENDER_SCALE;
	/** See CAIRODRIVE_HW_CANVAS in cairodrive.gradle. */
	private static final boolean useHardwareCanvas = BuildConfig.CAIRODRIVE_HW_CANVAS;
	/** Latched once the head unit's buffer proves it cannot take a hardware canvas. */
	private boolean hardwareCanvasFailed;
	/** Reused; allocating a Paint per frame is what the widget path was just fixed for. */
	private final Paint upscalePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
	private final Rect blitDst = new Rect();
	private int surfaceAdditionalWidth = 0;
	// Ratios are calculated dynamically using surfaceWidthMultiply
	private float minRatio = 0.5f;
	private float maxRatio = 0.5f;

	public void setCallback(@Nullable SurfaceRendererCallback callback) {
		this.callback = callback;
	}

	public interface SurfaceRendererCallback {
		void onFrameRendered(@NonNull Canvas canvas, @NonNull Rect visibleArea, @NonNull Rect stableArea);
		void onElevationChanging(float angle);
	}

	private void setupSurfaceView(@NonNull SurfaceContainer surfaceContainer) {
		if (getApp().useOpenGlRenderer()) {
			surfaceAdditionalWidth = (int) ((float) surfaceContainer.getWidth() * surfaceWidthMultiply);
		}

		surfaceView.setSurfaceParams(surfaceContainer.getWidth() + surfaceAdditionalWidth,
				surfaceContainer.getHeight(), surfaceContainer.getDpi());

		minRatio = (1f - surfaceWidthMultiply) / 2.0f;
		maxRatio = 1f - (1f - surfaceWidthMultiply) / 2.0f;
	}

	private void changeVisibleArea(@NonNull Rect visibleArea) {
		cachedVisibleArea = visibleArea;
		Log.i(TAG, "Visible area changed " + surface + ". stableArea: "
				+ stableArea + " visibleArea:" + visibleArea);
		SurfaceRenderer.this.visibleArea = visibleArea;
		if (!visibleArea.isEmpty() && mapView != null && surfaceContainer != null) {
			MapDisplayPositionManager displayPositionManager = getDisplayPositionManager();

			int visibleAreaHeight = visibleArea.height();
			int containerWidth = surfaceContainer.getWidth();
			int containerHeight = surfaceContainer.getHeight();

			int centerX = visibleArea.centerX();
			cachedRatioX = (float) centerX / containerWidth;

			float cameraCenterShiftX = 0.5f;
			if (offscreenMapRendererView != null) {
				float dRatio = 0.5f + (1.0f - surfaceWidthMultiply) * (((1.0f - maxRatio) + minRatio) * 0.5f);

				if (cachedRatioX < minRatio) {
					cameraCenterShiftX = 0.5f - (minRatio - cachedRatioX) * dRatio;
					cachedRatioX = minRatio;
				} else if (cachedRatioX > maxRatio) {
					cameraCenterShiftX = 0.5f + (cachedRatioX - maxRatio) * dRatio;
					cachedRatioX = maxRatio;
				}
			} else {
				cameraCenterShiftX = cachedRatioX;
			}

			float ratioY = cachedRatioY;
			float defaultRatioY = displayPositionManager.getNavigationMapPosition().getRatioY();
			if (defaultRatioY != cachedDefaultRatioY || (float) containerHeight / visibleAreaHeight > VISIBLE_AREA_Y_MIN_DETECTION_SIZE) {
				float centerY = (visibleAreaHeight * defaultRatioY) + visibleArea.top;
				ratioY = centerY / containerHeight;
				cachedRatioY = ratioY;
				cachedDefaultRatioY = defaultRatioY;
			}
			displayPositionManager.setCustomMapRatio(cameraCenterShiftX, ratioY);
		}
		renderFrame();
	}

	public final SurfaceCallback mSurfaceCallback = new SurfaceCallback() {
		@Override
		public void onSurfaceAvailable(@NonNull SurfaceContainer surfaceContainer) {
			synchronized (SurfaceRenderer.this) {
				Log.i(TAG, "Surface available " + surfaceContainer);
				if (surface != null) {
					surface.release();
				}

				SurfaceRenderer.this.surfaceContainer = surfaceContainer;
				surface = surfaceContainer.getSurface();
				setupSurfaceView(surfaceContainer);

				if (cachedVisibleArea != null) {
					changeVisibleArea(cachedVisibleArea);
				}

				darkMode = carContext.isDarkMode();
				OsmandMapTileView mapView = SurfaceRenderer.this.mapView;
				if (mapView != null) {
					mapView.setupRenderingView();
				}
				renderFrame();
			}
		}

		@Override
		public void onVisibleAreaChanged(@NonNull Rect visibleArea) {
			synchronized (SurfaceRenderer.this) {
				changeVisibleArea(visibleArea);
			}
		}

		@Override
		public void onStableAreaChanged(@NonNull Rect stableArea) {
			synchronized (SurfaceRenderer.this) {
				Log.i(TAG, "Stable area changed " + surface + ". stableArea: "
						+ stableArea + " visibleArea:" + visibleArea);
				SurfaceRenderer.this.stableArea = stableArea;
				renderFrame();
			}
		}

		@Override
		public void onSurfaceDestroyed(@NonNull SurfaceContainer surfaceContainer) {
			synchronized (SurfaceRenderer.this) {
				Log.i(TAG, "Surface destroyed");
				if (surface != null) {
					surface.release();
					surface = null;
				}
				OsmandMapTileView mapView = SurfaceRenderer.this.mapView;
				if (mapView != null) {
					getDisplayPositionManager().restoreMapRatio();
					mapView.setupRenderingView();
				}
			}
		}

		@Override
		public void onScroll(float distanceX, float distanceY) {
			synchronized (SurfaceRenderer.this) {
				OsmandMapTileView mapView = SurfaceRenderer.this.mapView;
				if (mapView != null) {
					mapView.scrollMap(distanceX, distanceY);
				}
			}
		}

		@Override
		public void onFling(float velocityX, float velocityY) {
			OsmandMapTileView mapView = SurfaceRenderer.this.mapView;
			if (mapView != null) {
				mapView.flingMap(0, 0, velocityX, velocityY);
			}
		}

		long lastScaleTime = 0;
		Boolean lastZoomDirection;

		@Override
		public void onScale(float focusX, float focusY, float scaleFactor) {
			boolean zoomDirection = scaleFactor > 1;
			if (System.currentTimeMillis() - lastScaleTime > PINCH_TO_ZOOM_ITERATION_DELAY || lastZoomDirection == null || lastZoomDirection != zoomDirection) {
				handleScale(focusX, focusY, scaleFactor);
				lastScaleTime = System.currentTimeMillis();
				lastZoomDirection = zoomDirection;
			}
		}
	};

	public SurfaceRenderer(@NonNull CarContext carContext, @NonNull Lifecycle lifecycle) {
		this.handler = new Handler();
		this.carContext = carContext;
		this.surfaceView = new CarSurfaceView(carContext, this);
		lifecycle.addObserver(this);
	}

	private void sendRenderFrameMsg() {
		if (!handler.hasMessages(MAP_RENDER_MESSAGE)) {
			Message msg = Message.obtain(handler, () -> {
				handler.removeMessages(MAP_RENDER_MESSAGE);
				renderFrame();
			});
			msg.what = MAP_RENDER_MESSAGE;
			handler.sendMessage(msg);
		}
	}

	@Override
	public void onCreate(@NonNull LifecycleOwner owner) {
		Log.i(TAG, "SurfaceRenderer created");
		try {
			carContext.getCarService(AppManager.class).setSurfaceCallback(mSurfaceCallback);
		} catch (SecurityException | HostException e) {
			Log.e(TAG, "setSurfaceCallback failed ", e);
		}
	}

	/**
	 * Callback called when the car configuration changes.
	 */
	public void onCarConfigurationChanged() {
		renderFrame();
	}

	@Override
	public void onUpdateFrame(MapRendererView mapRendererView) {
	}

	/**
	 * Callback called when OpenGL rendering result is ready and needs to be drawn on output canvas.
	 */
	@Override
	public void onFrameReady(MapRendererView mapRendererView) {
		// First real frame from the GL core - everything drawn before this was an empty bitmap.
		firstFrameReady = true;
		//renderFrame();
		sendRenderFrameMsg();
	}

	/**
	 * Handles the map zoom-in and zoom-out events.
	 */
	public void handleScale(float focusX, float focusY, float scaleFactor) {
		synchronized (this) {
			float x = focusX;
			float y = focusY;
			Rect visibleArea = this.visibleArea;
			if (visibleArea != null) {
				// If a focal point value is negative, use the center point of the visible area.
				if (x < 0) {
					x = visibleArea.centerX();
				}
				if (y < 0) {
					y = visibleArea.centerY();
				}
			}
			OsmandMapTileView mapView = this.mapView;
			if (mapView != null) {
				if (scaleFactor > 1) {
					mapView.zoomInAndAdjustTiltAngle();
				} else if (scaleFactor < 1) {
					mapView.zoomOutAndAdjustTiltAngle();
				}
			}
		}
	}

	/**
	 * Handles the map 2D/3D button press events.
	 */
	public void handleTilt() {
		synchronized (this) {
			if (mapView != null && mapView.getAnimatedDraggingThread() != null && offscreenMapRendererView != null) {
				int adjustedTiltAngle = mapView.getAdjustedTiltAngle(mapView.getZoom(), true);
				float newAngle = mapView.getElevationAngle() < DEFAULT_ELEVATION_ANGLE ? DEFAULT_ELEVATION_ANGLE : adjustedTiltAngle;
				getApp().getSettings().setLastKnownMapElevation(newAngle);
				mapView.getAnimatedDraggingThread().startTilting(newAngle, 0.0f);
			}
		}
	}

	/**
	 * Handles the map re-centering events.
	 */
	public void handleRecenter() {
		OsmandMapTileView mapView = this.mapView;
		if (mapView != null) {
			mapView.backToLocation();
		}
	}

	/**
	 * Updates the location coordinate string drawn on the surface.
	 */
	public void updateLocation(@Nullable Location location) {
		//renderFrame();
	}

	@NonNull
	private MapDisplayPositionManager getDisplayPositionManager() {
		return getApp().getMapViewTrackingUtilities().getMapDisplayPositionManager();
	}

	@NonNull
	private OsmandApplication getApp() {
		return (OsmandApplication) carContext.getApplicationContext();
	}

	public OsmandMapTileView getMapView() {
		return mapView;
	}

	public void setMapView(OsmandMapTileView mapView) {
		if (mapView == null) {
			stopOffscreenRenderer();
			return;
		}
		this.mapView = mapView;
		if (surface != null) {
			mapView.setView(surfaceView);
		}
		if (getApp().isApplicationInitializing()) {
			// Fire as soon as the renderer's ACTUAL precondition is met, not at the end of init.
			// setupOffscreenRenderer needs NativeCoreContext.getMapRendererContext() (ready at
			// NATIVE_OPEN_GL_INITIALIZED) and map data to draw (MAPS_INITIALIZED). Everything after
			// that in startApplicationBackground - region boundaries, favourites, the GPX database,
			// GPX load AND save, marker sync, search-UI init, the live-update sweep, the BRouter
			// bind, help articles - is irrelevant to putting a map on the head unit, yet the car
			// waited for all of it, and then for a post to the main looper on top.
			//
			// The 2026-08-04 drive log put a number on it: INDEX_REGION_BOUNDARIES alone measured
			// 1150-1300 ms, and it sits in that tail.
			//
			// The onFinish listener is deliberately KEPT as a safety net. setupOffscreenRenderer is
			// idempotent (it re-checks the renderer context and its own null state on every call),
			// so the worst case of firing twice is a wasted check - whereas the worst case of
			// firing too early with no fallback is a permanently black car screen.
			AppInitializer initializer = getApp().getAppInitializer();
			initializer.addListener(new AppInitializeListener() {
				@Override
				public void onProgress(@NonNull AppInitializer init, @NonNull AppInitEvents event) {
					if (event == AppInitEvents.MAPS_INITIALIZED) {
						init.removeListener(this);
						setupOffscreenRenderer();
					}
				}

				@Override
				public void onFinish(@NonNull AppInitializer init) {
					init.removeListener(this);
					setupOffscreenRenderer();
				}
			});
		} else
			setupOffscreenRenderer();
	}

	public synchronized void setupOffscreenRenderer() {
		Log.i(TAG, "setupOffscreenRenderer");
		if (getApp().useOpenGlRenderer()) {
			if (surface != null && surface.isValid()) {
				if (offscreenMapRendererView != null) {
					MapRendererContext mapRendererContext = NativeCoreContext.getMapRendererContext();
					if (mapRendererContext != null && mapRendererContext.getMapRendererView() != offscreenMapRendererView) {
						offscreenMapRendererView = null;
						firstFrameReady = false;
					}
				}
				if (offscreenMapRendererView == null) {
					MapRendererContext mapRendererContext = NativeCoreContext.getMapRendererContext();
					if (mapRendererContext != null) {
						MapRendererView mapRendererView = null;
						if (mapView != null && mapView.getMapRenderer() != null) {
							mapView.detachMapRenderer();
						}
						if (mapRendererContext.getMapRendererView() != null) {
							mapRendererView = mapRendererContext.getMapRendererView();
							mapRendererContext.setMapRendererView(null);
						}
						NativeCoreContext.setMapRendererContext(getApp(), surfaceView.getDensity());
						mapRendererContext = NativeCoreContext.getMapRendererContext();
						if (mapRendererContext != null) {
							if (surfaceContainer != null) {
								setupSurfaceView(surfaceContainer);
							}

							firstFrameReady = false;
							offscreenMapRendererView = new AtlasMapRendererView(carContext);

							boolean enableMSAA = getApp().getSettings().ENABLE_MSAA.get();

							mapRendererContext.presetMapRendererOptions(offscreenMapRendererView, enableMSAA);
							// Scaled dimensions, not the surface's. The phone path passes 0,0 which puts
							// the core in window mode; the car path passes explicit sizes, so this is
							// simply how big the offscreen framebuffer is - and therefore how many
							// pixels are read back and blitted every frame.
							offscreenMapRendererView.setupRenderer(carContext, scaled(getWidth()),
									scaled(getHeight()), mapRendererView);
							offscreenMapRendererView.setMinZoomLevel(ZoomLevel.swigToEnum(mapView.getMinZoom()));
							offscreenMapRendererView.setMaxZoomLevel(ZoomLevel.swigToEnum(mapView.getMaxZoom()));
							offscreenMapRendererView.setAzimuth(0);
							offscreenMapRendererView.setFlatEarth(!getApp().getSettings().SPHERICAL_MAP.get());
							offscreenMapRendererView.removeAllSymbolsProviders();
							offscreenMapRendererView.resumeSymbolsUpdate();
							offscreenMapRendererView.setSymbolsUpdateInterval(SYMBOLS_UPDATE_INTERVAL);
							offscreenMapRendererView.setMaximumFrameRate(MAX_FRAME_RATE);
							mapRendererContext.setMapRendererView(offscreenMapRendererView);
							mapView.setMinAllowedElevationAngle(MIN_ALLOWED_ELEVATION_ANGLE_AA);
							float elevationAngle = mapView.normalizeElevationAngle(getApp().getSettings().getLastKnownMapElevation());
							mapView.setMapRenderer(offscreenMapRendererView, false);
							mapView.setElevationAngle(elevationAngle);
							mapView.addElevationListener(this);
							getApp().getOsmandMap().getMapLayers().updateMapSource(mapView, null);
							PluginsHelper.refreshLayers(getApp(), null);
							offscreenMapRendererView.addListener(this);
							mapView.getAnimatedDraggingThread().toggleAnimations();

							if (cachedVisibleArea != null) {
								changeVisibleArea(cachedVisibleArea);
							}
						}
					}
				}
			}
		}
	}

	public synchronized void stopOffscreenRenderer() {
		Log.i(TAG, "stopOffscreenRenderer");
		if (offscreenMapRendererView != null) {
			if (mapView != null) {
				mapView.removeElevationListener(this);
				mapView.getAnimatedDraggingThread().toggleAnimations();
				if (mapView.getMapRenderer() == offscreenMapRendererView) {
					mapView.detachMapRenderer();
				}
			}
			MapRendererContext mapRendererContext = NativeCoreContext.getMapRendererContext();
			if (mapRendererContext != null) {
				mapRendererContext.suspendMapRendererView(offscreenMapRendererView);
			}
			offscreenMapRendererView = null;
		}
	}

	/** Applies {@link #renderScale}, never returning 0 - a zero-sized framebuffer is not valid. */
	private static int scaled(int value) {
		return Math.max(1, Math.round(value * renderScale));
	}

	public int getWidth() {
		return surfaceView.getWidth();
	}

	public int getHeight() {
		return surfaceView.getHeight();
	}

	public int getDpi() {
		return surfaceView.getDpi();
	}

	public float getDensity() {
		return surfaceView.getDensity();
	}

	public boolean hasSurface() {
		return surface != null && surface.isValid();
	}

	public boolean hasOffscreenRenderer() {
		return offscreenMapRendererView != null;
	}

	public void renderFrame() {
		if (mapView == null || surface == null || !surface.isValid()) {
			// Surface is not available, or has been destroyed, skip this frame.
			return;
		}
		// Dark mode is read once here and the DrawSettings built from it is the one actually
		// used - renderFrame used to discard this instance and allocate a second one from a
		// second isDarkMode() call, every frame.
		DrawSettings drawSettings = new DrawSettings(carContext.isDarkMode(), false);
		RotatedTileBox tileBox = mapView.getRotatedTileBox();
		try {
			renderFrame(tileBox, drawSettings);
		} catch (Exception ignored) {
			// Ignored
		}
	}

	public void renderFrame(RotatedTileBox tileBox, DrawSettings drawSettings) {
		if (mapView == null || surface == null || !surface.isValid()) {
			// Surface is not available, or has been destroyed, skip this frame.
			return;
		}
		// This whole method runs on the main looper, so its wall time is exactly the head-unit
		// stutter a driver feels. Logged to the on-device file (not logcat, which MIUI filters)
		// so "did the Android Auto smoothing work" is a grep over a pulled log rather than a guess.
		// Timed in five parts, because "the frame took 90 ms" does not say what to fix and the
		// candidates want completely different answers:
		//   lock  - waiting for the head unit to hand back a buffer. Nothing app-side helps;
		//           it means the display pipeline, not this code, is the bottleneck.
		//   read  - pulling the rendered map out of the GPU into a Bitmap. Scales with the
		//           offscreen size, which is what CAIRODRIVE_SURFACE_OVERSCAN controls.
		//   blit  - copying that Bitmap onto the head unit's canvas. Also scales with size.
		//   over  - OsmAnd's own overlay drawing, the only part that is ordinary Java work.
		//   wdgt  - the car screen's own callback: the speedometer and alarm widgets, which
		//           recompute and redraw themselves inside this locked canvas.
		//   post  - handing the finished buffer back to the head unit.
		//
		// `wdgt` is split out because without it that work fell into `post`, and `post` is the
		// bucket whose whole meaning is "the head unit is slow, nothing app-side can help".
		// A drive log would have been read as unfixable when the cost was this app's own widgets.
		// One drive with this in the log settles it.
		boolean timing = CairoDriveLogger.isEnabled();
		long frameStartNanos = timing ? System.nanoTime() : 0;
		// lockHardwareCanvas() when CAIRODRIVE_HW_CANVAS is on. A lockCanvas() canvas is never
		// hardware accelerated (AOSP), so the blit below runs on the CPU through Skia - and the
		// 2026-08-04 drive measured blit at 9.2 ms, 22% of a 46.9 ms frame. Both preconditions the
		// hardware path requires are already met here: this code fully repaints the surface every
		// frame (no partial updates to preserve), and nothing ever puts a GLES or video surface on
		// it - the offscreen renderer is a separate Pbuffer.
		//
		// Defaulted OFF, like CAIRODRIVE_RENDER_SCALE, because the AA host allocates this buffer
		// and may not have set the GPU usage flags the hardware path needs. When that happens the
		// lock throws or returns null rather than degrading, so the fallback below is mandatory
		// rather than defensive - and once it falls back it stays fallen back for the session,
		// because retrying a lock that has already failed once per frame is its own stall.
		Canvas canvas = null;
		if (useHardwareCanvas && !hardwareCanvasFailed) {
			try {
				canvas = surface.lockHardwareCanvas();
			} catch (Throwable t) {
				hardwareCanvasFailed = true;
				Log.w(TAG, "lockHardwareCanvas unavailable on this head unit, using software canvas", t);
			}
			if (canvas == null && !hardwareCanvasFailed) {
				hardwareCanvasFailed = true;
				Log.w(TAG, "lockHardwareCanvas returned null, using software canvas");
			}
		}
		if (canvas == null) {
			canvas = surface.lockCanvas(null);
		}
		long lockDoneNanos = timing ? System.nanoTime() : 0;
		long readDoneNanos = lockDoneNanos;
		long blitDoneNanos = lockDoneNanos;
		long overDoneNanos = lockDoneNanos;
		long widgetDoneNanos = lockDoneNanos;
		try {
			boolean newDarkMode = carContext.isDarkMode();
			boolean updateVectorRendering = drawSettings.isUpdateVectorRendering() || darkMode != newDarkMode;
			darkMode = newDarkMode;
			drawSettings = new DrawSettings(newDarkMode, updateVectorRendering);
			Bitmap mapBitmap = offscreenMapRendererView != null && firstFrameReady
					? offscreenMapRendererView.getBitmap() : null;
			if (timing) {
				readDoneNanos = System.nanoTime();
			}
			if (mapBitmap != null) {
				// No drawColor() first: the map bitmap is opaque and covers the whole surface,
				// so clearing underneath it was a full-screen fill discarded on the very next
				// call - once per frame, on the main looper, for nothing.
				//
				// Except when it does NOT cover it. onSurfaceAvailable installs a larger
				// SurfaceContainer and renders immediately, before the offscreen renderer has
				// been rebuilt at the new size, so for a few frames after a head-unit reconnect
				// or a panel resize an older, smaller bitmap is blitted at (0,0) onto a bigger
				// canvas. lockCanvas returns an uncleared swap-chain buffer, so the uncovered
				// strip would show a stale frame rather than background.
				if (renderScale == 1.0f
						&& (mapBitmap.getWidth() < canvas.getWidth() || mapBitmap.getHeight() < canvas.getHeight())) {
					canvas.drawColor(newDarkMode ? EMPTY_FRAME_NIGHT_COLOR : EMPTY_FRAME_DAY_COLOR);
				}
				if (renderScale != 1.0f) {
					// Stretch to fill. The destination covers the whole canvas, so no background
					// shows through and the undersized-bitmap guard above is not needed on this
					// path - the bitmap being smaller is the intent here, not a resize glitch.
					blitDst.set(0, 0, canvas.getWidth(), canvas.getHeight());
					canvas.drawBitmap(mapBitmap, null, blitDst, upscalePaint);
				} else {
					float leftOffset = 0.0f;
					if (surfaceAdditionalWidth != 0) {
						leftOffset = -surfaceAdditionalWidth * ((maxRatio - cachedRatioX) / (maxRatio - minRatio));
					}
					canvas.drawBitmap(mapBitmap, leftOffset, 0, null);
				}
			} else {
				// Nothing to show yet - the offscreen renderer is still being set up, which is
				// what the driver sees as "the map is blank for a bit" at start and after the
				// head unit reconnects. Light grey is the worst possible colour for that at
				// night; match the map's own background so the gap reads as a map still loading
				// rather than a broken screen.
				canvas.drawColor(newDarkMode ? EMPTY_FRAME_NIGHT_COLOR : EMPTY_FRAME_DAY_COLOR);
			}
			if (timing) {
				blitDoneNanos = System.nanoTime();
			}
			mapView.drawOverMap(canvas, tileBox, drawSettings);
			if (timing) {
				overDoneNanos = System.nanoTime();
			}
			SurfaceRendererCallback callback = this.callback;
			if (callback != null) {
				Rect visibleArea = this.visibleArea;
				Rect stableArea = this.stableArea;
				if (visibleArea != null && stableArea != null) {
					callback.onFrameRendered(canvas, visibleArea, stableArea);
				}
			}
			if (timing) {
				widgetDoneNanos = System.nanoTime();
			}
		} finally {
			surface.unlockCanvasAndPost(canvas);
			if (timing) {
				long endNanos = System.nanoTime();
				logFrameTiming((endNanos - frameStartNanos) / 1_000_000L,
						(lockDoneNanos - frameStartNanos) / 1_000_000L,
						(readDoneNanos - lockDoneNanos) / 1_000_000L,
						(blitDoneNanos - readDoneNanos) / 1_000_000L,
						(overDoneNanos - blitDoneNanos) / 1_000_000L,
						(widgetDoneNanos - overDoneNanos) / 1_000_000L,
						(endNanos - widgetDoneNanos) / 1_000_000L);
			}
		}
	}

	/** Slow-frame threshold in ms: at the car cap of 20fps a frame has ~50ms; over this it dropped one. */
	private static final long SLOW_FRAME_MS = 60;
	/** How many frames a rolling summary covers - ~10s at the 20fps car cap. */
	private static final int FRAME_SUMMARY_INTERVAL = 200;
	private int frameCount;
	private long frameWallSumMs;
	private long frameWallMaxMs;
	private int slowFrameCount;

	/**
	 * Records one car frame's main-thread cost to the diagnostic file. Slow frames are written
	 * individually so a stutter is pinpointed; a summary every {@link #FRAME_SUMMARY_INTERVAL}
	 * frames gives the steady state. The file writer is non-blocking, so this never adds to the
	 * frame it is measuring, and the whole method is skipped when file logging is compiled out.
	 */
	private long lockSumMs;
	private long readSumMs;
	private long blitSumMs;
	private long overSumMs;
	private long widgetSumMs;
	private long postSumMs;

	/**
	 * Synchronized because renderFrame is reached from two threads: OsmandMapTileView calls it
	 * holding this object's monitor, while the MAP_RENDER_MESSAGE handler calls the no-arg
	 * overload without it. These counters are plain int/long, so unsynchronized read-modify-write
	 * would quietly under-count - and the whole point of them is to be trusted after a drive.
	 * The lock is on the same monitor the map-view path already holds, so that path re-enters it
	 * rather than contending, and this is a leaf method with nothing to deadlock against.
	 */
	private synchronized void logFrameTiming(long wallMs, long lockMs, long readMs, long blitMs,
	                                         long overMs, long widgetMs, long postMs) {
		frameCount++;
		frameWallSumMs += wallMs;
		lockSumMs += lockMs;
		readSumMs += readMs;
		blitSumMs += blitMs;
		overSumMs += overMs;
		widgetSumMs += widgetMs;
		postSumMs += postMs;
		if (wallMs > frameWallMaxMs) {
			frameWallMaxMs = wallMs;
		}
		if (wallMs >= SLOW_FRAME_MS) {
			slowFrameCount++;
			CairoDriveLogger.getInstance().log("CD_FRAME", "slow wallMs=" + wallMs
					+ " lock=" + lockMs + " read=" + readMs + " blit=" + blitMs
					+ " over=" + overMs + " wdgt=" + widgetMs + " post=" + postMs);
		}
		if (frameCount >= FRAME_SUMMARY_INTERVAL) {
			// The split is the whole point of the summary: whichever of these dominates is the
			// thing to fix, and they have different fixes. A large `lock` means the head unit is
			// the bottleneck and no app-side change will help. Large `read` or `blit` means the
			// per-frame GPU readback and canvas copy dominate, which is what the overscan setting
			// attacks. A large `over` would mean OsmAnd's own overlay drawing, the only part that
			// is plain Java and the only part easily optimised further.
			CairoDriveLogger.getInstance().log("CD_FRAME", "summary frames=" + frameCount
					+ " avgMs=" + (frameWallSumMs / frameCount)
					+ " maxMs=" + frameWallMaxMs + " slow=" + slowFrameCount
					+ " overscan=" + surfaceWidthMultiply
					+ " renderScale=" + renderScale
					// The head unit's ACTUAL surface, which nothing was recording. Everything
					// about whether reducing renderScale is worth it depends on how many pixels
					// there are to begin with and how many of them a street label gets, and both
					// were being estimated from the car's spec sheet. dpi is what OsmAnd sizes
					// map text from, so label height in pixels is dp * dpi/160 * renderScale.
					+ " surface=" + getWidth() + "x" + getHeight()
					+ " dpi=" + getDpi()
					+ " avgLock=" + (lockSumMs / frameCount)
					+ " avgRead=" + (readSumMs / frameCount)
					+ " avgBlit=" + (blitSumMs / frameCount)
					+ " avgOver=" + (overSumMs / frameCount)
					+ " avgWidget=" + (widgetSumMs / frameCount)
					+ " avgPost=" + (postSumMs / frameCount));
			lockSumMs = 0;
			readSumMs = 0;
			blitSumMs = 0;
			overSumMs = 0;
			widgetSumMs = 0;
			postSumMs = 0;
			frameCount = 0;
			frameWallSumMs = 0;
			frameWallMaxMs = 0;
			slowFrameCount = 0;
		}
	}


	@Nullable
	public Rect getVisibleArea() {
		return visibleArea;
	}

	public double getVisibleAreaWidth() {
		return visibleArea != null ? visibleArea.width() : 0f;
	}

	public int getSurfaceAdditionalWidth() {
		return surfaceAdditionalWidth;
	}

	public float getCachedRatioX() {
		return cachedRatioX;
	}

	public float getCachedRatioY() {
		return cachedRatioY;
	}

	@Override
	public void onElevationChanging(float angle) {
		SurfaceRendererCallback callback = this.callback;
		if (callback != null) {
			callback.onElevationChanging(angle);
		}
	}

	@Override
	public void onStopChangingElevation(float angle) {
	}
}
