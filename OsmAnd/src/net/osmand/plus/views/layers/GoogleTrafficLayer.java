package net.osmand.plus.views.layers;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.core.android.MapRendererView;
import net.osmand.core.jni.PointI;
import net.osmand.core.jni.QVectorPointI;
import net.osmand.core.jni.VectorLine;
import net.osmand.core.jni.VectorLineBuilder;
import net.osmand.core.jni.VectorLinesCollection;
import net.osmand.data.LatLon;
import net.osmand.data.QuadRect;
import net.osmand.data.RotatedTileBox;
import net.osmand.plus.OsmAndLocationProvider;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.routing.GoogleTrafficHelper;
import net.osmand.plus.routing.GoogleTrafficHelper.CongestionSpan;
import net.osmand.plus.routing.GoogleTrafficHelper.TrafficSnapshot;
import net.osmand.plus.utils.NativeUtilities;
import net.osmand.plus.views.OsmandMap;
import net.osmand.plus.views.OsmandMapTileView;
import net.osmand.plus.views.layers.base.OsmandMapLayer;
import net.osmand.plus.views.layers.geometry.GeometryWayDrawer;
import net.osmand.util.MapUtils;

/**
 * Paints the Google Routes congestion spans ({@link GoogleTrafficHelper}) over the map:
 * orange = slow, red = jam - on the active route while navigating, or on the road corridor ahead
 * in free driving. Sits just above the route line (z-order 1.2) and below POIs/markers, alongside
 * the TomTom/HERE raster traffic overlays. Also owns the free-drive location listener that
 * triggers the corridor polls. Draws nothing without fresh data, so it costs nothing when the
 * feature is off or the key is absent.
 */
public class GoogleTrafficLayer extends OsmandMapLayer {

	private static final int COLOR_SLOW = 0xCCFF9800; // semi-transparent orange
	private static final int COLOR_JAM = 0xCCE53935;  // semi-transparent red
	private static final float LINE_WIDTH_DP = 7f;    // narrower than the route line so its casing stays visible

	private OsmandApplication app;
	private Paint slowPaint;
	private Paint jamPaint;
	private final Path spanPath = new Path();
	private OsmAndLocationProvider.OsmAndLocationListener freeDriveListener;

	private VectorLinesCollection vectorLinesCollection;
	// Renderer the collection was handed to: a symbols provider must only ever be removed from
	// the renderer that actually took it, and a swap can null our getMapRenderer() first.
	private MapRendererView linesRenderer;
	private int builtVersion = -1;
	// Legacy-canvas fast visibility test: per-span {minLat, maxLat, minLon, maxLon}, rebuilt only
	// when the snapshot version changes instead of re-walking every point each buffer redraw.
	private double[][] spanBounds;
	private int spanBoundsVersion = -1;
	// One scheduled repaint per snapshot so spans vanish when the TTL passes on an idle map.
	private int expiryScheduledVersion = -1;

	public GoogleTrafficLayer(@NonNull Context ctx) {
		super(ctx);
	}

	@Override
	public void initLayer(@NonNull OsmandMapTileView view) {
		super.initLayer(view);
		app = getApplication();
		slowPaint = createPaint(COLOR_SLOW);
		jamPaint = createPaint(COLOR_JAM);
		// Free-drive polling: navigation polls hook RoutingHelper, but with no route only a plain
		// location listener sees movement - GoogleTrafficHelper ignores it while navigating.
		freeDriveListener = location -> {
			GoogleTrafficHelper.onFreeDriveLocation(app, location);
			// Closures matter before a route exists too - same zone-scoped sync as navigation.
			// Mirrors the isFollowingMode() guard GoogleTrafficHelper.onFreeDriveLocation applies:
			// both entries share one 10-minute slot but ask for different zones, so without this a
			// navigating driver could get the wide ambient box instead of the 2 km route corridor
			// that the on-route closure check needs.
			if (!app.getRoutingHelper().isFollowingMode()) {
				net.osmand.plus.routing.ClosureSyncHelper.onLocationUpdate(app, location, false);
			}
		};
		app.getLocationProvider().addLocationListener(freeDriveListener);
	}

	@Override
	public void destroyLayer() {
		super.destroyLayer();
		if (freeDriveListener != null) {
			app.getLocationProvider().removeLocationListener(freeDriveListener);
			freeDriveListener = null;
		}
	}

	private static Paint createPaint(int color) {
		Paint paint = new Paint();
		paint.setStyle(Paint.Style.STROKE);
		paint.setAntiAlias(true);
		paint.setStrokeCap(Paint.Cap.ROUND);
		paint.setStrokeJoin(Paint.Join.ROUND);
		paint.setColor(color);
		return paint;
	}

	@Override
	public void onPrepareBufferImage(Canvas canvas, RotatedTileBox tileBox, DrawSettings settings) {
		super.onPrepareBufferImage(canvas, tileBox, settings);
		TrafficSnapshot snapshot = getActiveSnapshot();
		scheduleExpiryRepaint(snapshot);
		MapRendererView mapRenderer = getMapRenderer();
		if (mapRenderer != null) {
			if (snapshot == null) {
				clearVectorLinesCollection();
				builtVersion = -1;
			} else if (vectorLinesCollection == null || builtVersion != snapshot.version
					|| mapRendererChanged || mapActivityInvalidated) {
				clearVectorLinesCollection();
				buildVectorLines(mapRenderer, snapshot);
				builtVersion = snapshot.version;
			}
			mapRendererChanged = false;
			mapActivityInvalidated = false;
		} else if (snapshot != null) {
			drawSpans(canvas, tileBox, snapshot);
		}
	}

	@Override
	public void onDraw(Canvas canvas, RotatedTileBox tileBox, DrawSettings settings) {
	}

	/** Fresh, non-empty data with the feature on - otherwise null. Not gated on navigation:
	 *  free-drive corridor spans paint too, and stale data is cleared by TTL / reset(). */
	@Nullable
	private TrafficSnapshot getActiveSnapshot() {
		if (!app.getSettings().GOOGLE_TRAFFIC_ON_ROUTE.get()) {
			return null;
		}
		TrafficSnapshot snapshot = GoogleTrafficHelper.getSnapshot();
		// Span age runs on spansTimeMs: cheap delay-polls refresh timeMs without re-fetching spans.
		if (snapshot == null || snapshot.spans.isEmpty()
				|| SystemClock.elapsedRealtime() - snapshot.spansTimeMs > GoogleTrafficHelper.SNAPSHOT_TTL_MS) {
			return null;
		}
		return snapshot;
	}

	/**
	 * Frames only happen when something redraws the map - on an idle map, an expiring snapshot
	 * would otherwise stay painted past its TTL. Post one delayed repaint per snapshot version,
	 * timed just after the expiry, so stale congestion always clears itself.
	 */
	private void scheduleExpiryRepaint(@Nullable TrafficSnapshot snapshot) {
		if (snapshot == null || snapshot.version == expiryScheduledVersion) {
			return;
		}
		expiryScheduledVersion = snapshot.version;
		long untilExpiry = GoogleTrafficHelper.SNAPSHOT_TTL_MS
				- (SystemClock.elapsedRealtime() - snapshot.spansTimeMs);
		app.runInUIThread(() -> {
			OsmandMap map = app.getOsmandMap();
			if (map != null) {
				map.refreshMap();
			}
		}, Math.max(0, untilExpiry) + 1000);
	}

	private void drawSpans(Canvas canvas, RotatedTileBox tileBox, TrafficSnapshot snapshot) {
		QuadRect bounds = tileBox.getLatLonBounds();
		float strokeWidth = LINE_WIDTH_DP * tileBox.getDensity();
		slowPaint.setStrokeWidth(strokeWidth);
		jamPaint.setStrokeWidth(strokeWidth);
		if (spanBoundsVersion != snapshot.version) {
			rebuildSpanBounds(snapshot);
		}
		// The buffer canvas is pre-rotated and getPixXFromLatLon already applies the map rotation,
		// so counter-rotate while drawing (same as RouteLayer/DistanceRulerControlLayer do).
		canvas.rotate(-tileBox.getRotate(), tileBox.getCenterPixelX(), tileBox.getCenterPixelY());
		for (int s = 0; s < snapshot.spans.size(); s++) {
			CongestionSpan span = snapshot.spans.get(s);
			double[] b = spanBounds[s];
			// b = {minLat, maxLat, minLon, maxLon}; QuadRect lat/lon bounds: left/right = min/max
			// longitude, top/bottom = max/min latitude.
			if (b[3] < bounds.left || b[2] > bounds.right || b[1] < bounds.bottom || b[0] > bounds.top) {
				continue;
			}
			spanPath.reset();
			for (int i = span.start; i <= span.end; i++) {
				LatLon point = snapshot.points.get(i);
				float x = tileBox.getPixXFromLatLon(point.getLatitude(), point.getLongitude());
				float y = tileBox.getPixYFromLatLon(point.getLatitude(), point.getLongitude());
				if (i == span.start) {
					spanPath.moveTo(x, y);
				} else {
					spanPath.lineTo(x, y);
				}
			}
			canvas.drawPath(spanPath, span.jam ? jamPaint : slowPaint);
		}
		canvas.rotate(tileBox.getRotate(), tileBox.getCenterPixelX(), tileBox.getCenterPixelY());
	}

	private void rebuildSpanBounds(TrafficSnapshot snapshot) {
		double[][] bounds = new double[snapshot.spans.size()][4];
		for (int s = 0; s < snapshot.spans.size(); s++) {
			CongestionSpan span = snapshot.spans.get(s);
			double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
			double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
			for (int i = span.start; i <= span.end; i++) {
				LatLon point = snapshot.points.get(i);
				minLat = Math.min(minLat, point.getLatitude());
				maxLat = Math.max(maxLat, point.getLatitude());
				minLon = Math.min(minLon, point.getLongitude());
				maxLon = Math.max(maxLon, point.getLongitude());
			}
			bounds[s][0] = minLat;
			bounds[s][1] = maxLat;
			bounds[s][2] = minLon;
			bounds[s][3] = maxLon;
		}
		spanBounds = bounds;
		spanBoundsVersion = snapshot.version;
	}

	private void buildVectorLines(@NonNull MapRendererView mapRenderer, @NonNull TrafficSnapshot snapshot) {
		VectorLinesCollection collection = new VectorLinesCollection();
		int baseOrder = getBaseOrder();
		int lineId = 1;
		float width = LINE_WIDTH_DP * density * GeometryWayDrawer.getVectorLineScale(app);
		for (CongestionSpan span : snapshot.spans) {
			QVectorPointI points31 = new QVectorPointI();
			for (int i = span.start; i <= span.end; i++) {
				LatLon point = snapshot.points.get(i);
				points31.add(new PointI(MapUtils.get31TileNumberX(point.getLongitude()),
						MapUtils.get31TileNumberY(point.getLatitude())));
			}
			VectorLineBuilder builder = new VectorLineBuilder();
			builder.setBaseOrder(baseOrder--)
					.setIsHidden(false)
					.setLineId(lineId++)
					.setLineWidth(width)
					.setPoints(points31)
					.setEndCapStyle(VectorLine.EndCapStyle.BUTT.swigValue())
					.setFillColor(NativeUtilities.createFColorARGB(span.jam ? COLOR_JAM : COLOR_SLOW));
			builder.buildAndAddToCollection(collection);
		}
		vectorLinesCollection = collection;
		linesRenderer = mapRenderer;
		mapRenderer.addSymbolsProvider(collection);
	}

	private void clearVectorLinesCollection() {
		if (vectorLinesCollection != null) {
			if (linesRenderer != null) {
				linesRenderer.removeSymbolsProvider(vectorLinesCollection);
			}
			vectorLinesCollection = null;
			linesRenderer = null;
		}
	}

	@Override
	protected void cleanupResources() {
		super.cleanupResources();
		clearVectorLinesCollection();
		builtVersion = -1;
	}

	@Override
	public boolean drawInScreenPixels() {
		return false;
	}
}
