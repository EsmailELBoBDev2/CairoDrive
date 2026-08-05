package net.osmand.plus.views.layers;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

// net.osmand.core.android, not net.osmand.core.jni: the jni package is the raw SWIG binding and
// has no MapRendererView. Every other layer imports the android wrapper, which is what
// OsmandMapLayer.getMapRenderer() returns.
import net.osmand.core.android.MapRendererView;
import net.osmand.core.jni.PointI;
import net.osmand.core.jni.QVectorPointI;
import net.osmand.core.jni.VectorLineBuilder;
import net.osmand.core.jni.VectorLinesCollection;
import net.osmand.data.LatLon;
import net.osmand.data.QuadRect;
import net.osmand.data.RotatedTileBox;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.routing.GoogleTrafficHelper;
import net.osmand.plus.routing.GoogleTrafficHelper.CongestionSpan;
import net.osmand.plus.routing.GoogleTrafficHelper.TrafficSnapshot;
import net.osmand.plus.utils.AndroidUtils;
import net.osmand.plus.utils.NativeUtilities;
import net.osmand.plus.views.OsmandMapTileView;
import net.osmand.plus.views.layers.base.OsmandMapLayer;
import net.osmand.util.MapUtils;

import java.util.List;

/**
 * Paints {@link GoogleTrafficHelper}'s congestion spans on top of the active route.
 *
 * <p>Sits just above the route line so the colours land on the route but stay under POIs and
 * markers, and is drawn deliberately NARROWER than the route casing so the route's own outline
 * still reads through - the driver should see "my route, with a red stretch on it", not a
 * disconnected red worm.
 *
 * <p>When the feature is off, navigation is not running, or the data has aged out, this draws
 * literally nothing and allocates nothing. That is the point of {@link #getActiveSnapshot()}
 * gating every path.
 */
public class GoogleTrafficLayer extends OsmandMapLayer {

	/** Orange - SLOW. */
	private static final int COLOR_SLOW = 0xCCFF9800;
	/** Red - TRAFFIC_JAM. */
	private static final int COLOR_JAM = 0xCCE53935;
	private static final float WIDTH_DP = 7f;
	/** Above the route line, below markers. */
	private static final int BASE_ORDER = -180000;

	private Paint slowPaint;
	private Paint jamPaint;
	private float widthPx;

	private VectorLinesCollection collection;
	private int builtVersion = -1;
	private boolean builtForRenderer;

	public GoogleTrafficLayer(@NonNull Context context) {
		super(context);
	}

	@Override
	public void initLayer(@NonNull OsmandMapTileView view) {
		super.initLayer(view);
		widthPx = AndroidUtils.dpToPx(getContext(), WIDTH_DP);
		slowPaint = createPaint(COLOR_SLOW);
		jamPaint = createPaint(COLOR_JAM);
	}

	@NonNull
	private Paint createPaint(int color) {
		Paint paint = new Paint();
		paint.setAntiAlias(true);
		paint.setStyle(Paint.Style.STROKE);
		paint.setStrokeCap(Paint.Cap.ROUND);
		paint.setStrokeJoin(Paint.Join.ROUND);
		paint.setStrokeWidth(widthPx);
		paint.setColor(color);
		return paint;
	}

	/**
	 * The single gate. Null unless the toggle is on, navigation is running, and the SPANS are
	 * fresh.
	 *
	 * <p>Freshness is measured on {@code spansTimeMs}, not {@code timeMs}: a cheap delay poll
	 * carries the previous colours forward without re-fetching them, so ageing on the snapshot's
	 * own timestamp would keep stale paint alive indefinitely.
	 */
	@Nullable
	private TrafficSnapshot getActiveSnapshot() {
		OsmandApplication app = getApplication();
		if (app == null || !app.getSettings().GOOGLE_TRAFFIC_ON_ROUTE.get()) {
			return null;
		}
		if (!app.getRoutingHelper().isFollowingMode()) {
			return null;
		}
		TrafficSnapshot snapshot = GoogleTrafficHelper.getSnapshot();
		if (snapshot == null || snapshot.spans.isEmpty() || snapshot.points.size() < 2) {
			return null;
		}
		// The PAINT ttl, which tracks the spans ladder, rather than the fixed decision TTL. Late in
		// a long drive spans are polled an hour apart by design; blanking the overlay 50 minutes out
		// of every 60 would have hidden data that had already been paid for on the Enterprise SKU.
		// The router keeps the stricter SNAPSHOT_TTL_MS for anything that steers the car.
		if (System.currentTimeMillis() - snapshot.spansTimeMs > GoogleTrafficHelper.spansPaintTtlMs()) {
			return null;
		}
		return snapshot;
	}

	@Override
	public void onPrepareBufferImage(Canvas canvas, RotatedTileBox tileBox, DrawSettings settings) {
		super.onPrepareBufferImage(canvas, tileBox, settings);
		TrafficSnapshot snapshot = getActiveSnapshot();
		MapRendererView mapRenderer = getMapRenderer();
		if (mapRenderer != null) {
			drawOpenGl(mapRenderer, snapshot);
		} else if (snapshot != null) {
			drawCanvas(canvas, tileBox, snapshot);
		}
	}

	// ------------------------------------------------------------------ OpenGL

	private void drawOpenGl(@NonNull MapRendererView mapRenderer, @Nullable TrafficSnapshot snapshot) {
		if (snapshot == null) {
			clearCollection(mapRenderer);
			return;
		}
		boolean stillAttached = collection != null && mapRenderer.hasSymbolsProvider(collection);
		// Rebuilt only when the DATA changed or the renderer dropped it - not per frame. Vector
		// line tessellation is not free and this runs on the draw path.
		if (stillAttached && builtVersion == snapshot.version && builtForRenderer) {
			return;
		}
		clearCollection(mapRenderer);

		VectorLinesCollection built = new VectorLinesCollection();
		int lineId = 1;
		for (CongestionSpan span : snapshot.spans) {
			QVectorPointI points = new QVectorPointI();
			for (int i = span.start; i <= span.end && i < snapshot.points.size(); i++) {
				LatLon point = snapshot.points.get(i);
				points.add(new PointI(MapUtils.get31TileNumberX(point.getLongitude()),
						MapUtils.get31TileNumberY(point.getLatitude())));
			}
			if (points.size() < 2) {
				continue;
			}
			VectorLineBuilder builder = new VectorLineBuilder();
			builder.setPoints(points)
					.setIsHidden(false)
					.setLineId(lineId++)
					.setLineWidth(widthPx)
					.setApproximationEnabled(false)
					.setBaseOrder(BASE_ORDER)
					.setFillColor(NativeUtilities.createFColorARGB(span.jam ? COLOR_JAM : COLOR_SLOW));
			builder.buildAndAddToCollection(built);
		}
		mapRenderer.addSymbolsProvider(built);
		collection = built;
		builtVersion = snapshot.version;
		builtForRenderer = true;
	}

	private void clearCollection(@Nullable MapRendererView mapRenderer) {
		if (collection != null) {
			if (mapRenderer != null && mapRenderer.hasSymbolsProvider(collection)) {
				mapRenderer.removeSymbolsProvider(collection);
			}
			collection = null;
		}
		builtVersion = -1;
		builtForRenderer = false;
	}

	// ------------------------------------------------------------------ legacy canvas

	private void drawCanvas(@NonNull Canvas canvas, @NonNull RotatedTileBox tileBox,
	                        @NonNull TrafficSnapshot snapshot) {
		QuadRect bounds = tileBox.getLatLonBounds();
		// Same pattern as RouteLayer: the tile box is already rotated, so undo it before drawing
		// in pixel space, then let the canvas transform put it back.
		canvas.rotate(-tileBox.getRotate(), tileBox.getCenterPixelX(), tileBox.getCenterPixelY());
		List<LatLon> points = snapshot.points;
		Path path = new Path();
		for (CongestionSpan span : snapshot.spans) {
			if (!intersects(points, span, bounds)) {
				continue;
			}
			path.reset();
			boolean started = false;
			for (int i = span.start; i <= span.end && i < points.size(); i++) {
				LatLon point = points.get(i);
				float x = tileBox.getPixXFromLatLon(point.getLatitude(), point.getLongitude());
				float y = tileBox.getPixYFromLatLon(point.getLatitude(), point.getLongitude());
				if (!started) {
					path.moveTo(x, y);
					started = true;
				} else {
					path.lineTo(x, y);
				}
			}
			if (started) {
				canvas.drawPath(path, span.jam ? jamPaint : slowPaint);
			}
		}
		canvas.rotate(tileBox.getRotate(), tileBox.getCenterPixelX(), tileBox.getCenterPixelY());
	}

	/** Cheap cull: skip a span entirely when none of its points are on screen. */
	private boolean intersects(@NonNull List<LatLon> points, @NonNull CongestionSpan span,
	                           @NonNull QuadRect bounds) {
		for (int i = span.start; i <= span.end && i < points.size(); i++) {
			LatLon point = points.get(i);
			if (point.getLatitude() >= bounds.bottom && point.getLatitude() <= bounds.top
					&& point.getLongitude() >= bounds.left && point.getLongitude() <= bounds.right) {
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ lifecycle

	/**
	 * Intentionally empty - all drawing happens in {@link #onPrepareBufferImage}.
	 *
	 * <p>{@code onDraw} is abstract on the base class so it has to be here, but both render paths
	 * reach this layer through the buffer callback instead: the legacy renderer calls
	 * {@code onPrepareBufferImage} for every layer from {@code refreshBufferImage}, and the OpenGL
	 * path calls it from the main draw loop whenever a map renderer exists. Painting congestion
	 * again here would draw it twice on the legacy path, once into the buffer bitmap and once over
	 * the top - at a different rotation, because the buffer is drawn pre-rotated.
	 */
	@Override
	public void onDraw(Canvas canvas, RotatedTileBox tileBox, DrawSettings settings) {
	}

	@Override
	public boolean drawInScreenPixels() {
		return false;
	}

	@Override
	protected void cleanupResources() {
		super.cleanupResources();
		clearCollection(getMapRenderer());
	}
}
