package net.osmand.plus.views.layers;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import net.osmand.data.RotatedTileBox;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.auto.NavigationSession;
import net.osmand.plus.auto.SurfaceRenderer;
import net.osmand.plus.cairodrive.providers.CairoDriveProviders;
import net.osmand.plus.cairodrive.providers.CairoDriveProviders.HazardBanner;
import net.osmand.plus.cairodrive.providers.TrafficAwareRouting;
import net.osmand.plus.routing.RoutingHelper;
import net.osmand.plus.views.OsmandMapTileView;
import net.osmand.plus.views.layers.base.OsmandMapLayer;
import net.osmand.util.Algorithms;

/**
 * The one place the provider stack becomes visible to the driver.
 *
 * <p>Draws up to two chips at the top of the map:
 * <ul>
 *   <li>the {@link HazardBanner} published by whichever provider currently holds
 *       {@link CairoDriveProviders.Capability#HAZARD} - dust, low visibility, sun glare;</li>
 *   <li>how much of the remaining time is traffic, from {@link TrafficAwareRouting}.</li>
 * </ul>
 *
 * <p><b>Why a map layer and not a widget.</b> This fork's Android Auto surface and the phone map
 * are the SAME {@link OsmandMapTileView} - {@code OsmandMap.setupRenderingView} hands the phone's
 * instance straight to the car session - and {@code drawOverMap} runs the layer list on both. One
 * layer therefore renders on both screens, whereas a widget would have needed the phone's view
 * hierarchy and a second hand-placed blit inside {@code NavigationScreen.onFrameRendered}, with two
 * copies of the layout to keep in step.
 *
 * <p><b>Cost.</b> This lands in the {@code over} bucket, which the 2026-08-04 drive measured at
 * 25.9 ms of a 46.9 ms frame - the one bucket that cannot afford a new tenant. So the frame path
 * does no text measurement and no layout: it compares two cache keys and, if they match, issues at
 * most two {@code drawRoundRect} plus two {@code drawText} calls against pre-built paints. When
 * there is no hazard and no delay - the overwhelmingly common case - it costs a volatile read, a
 * null test and a return.
 */
public class HazardBannerLayer extends OsmandMapLayer {

	/** Amber. Matches the alarm widget's warning tone rather than inventing a second one. */
	private static final int WARN_BG = 0xF2FFA000;
	private static final int WARN_FG = 0xFF000000;
	/** Neutral dark: worth knowing, not worth alarming about. See PROVIDERS.md 3.5. */
	private static final int INFO_BG = 0xE6263238;
	private static final int INFO_FG = 0xFFFFFFFF;
	/** Red, and deliberately a different colour from the hazard chip - a different kind of fact. */
	private static final int DELAY_BG = 0xF2D32F2F;
	private static final int DELAY_FG = 0xFFFFFFFF;

	private static final float TEXT_SP = 15f;
	private static final float CHIP_HEIGHT_DP = 32f;
	private static final float CHIP_PAD_DP = 12f;
	private static final float CHIP_RADIUS_DP = 8f;
	private static final float CHIP_GAP_DP = 6f;
	private static final float TOP_MARGIN_DP = 8f;

	/**
	 * Below this, the delay is inside the noise of the flow samples and of the offline engine's
	 * own estimate. Reporting "+1 min" every time a single segment dips would train the driver to
	 * ignore the chip, which costs the times it is right.
	 */
	private static final long MIN_DELAY_SECONDS = 120;

	/** See {@link #delayText} - the draw path may not walk the route. */
	private static final long DELAY_RECOMPUTE_MS = 2000;

	private Paint chipPaint;
	private Paint textPaint;
	private float chipHeight;
	private float chipPad;
	private float chipRadius;
	private float chipGap;
	private float topMargin;

	private final RectF chipRect = new RectF();
	private final Rect textBounds = new Rect();

	/** Last drawn content, so a steady state re-measures nothing. */
	private String cachedHazardText;
	private float cachedHazardWidth;
	private String cachedDelayText;
	private float cachedDelayWidth;

	/** Throttle state for {@link #delayText}. Main thread only, so unsynchronised is correct. */
	private String lastDelayText;
	private long lastDelayComputedMs;

	/**
	 * Resolved once per activity attach, never per frame: {@code findViewById} walks the hierarchy
	 * and this is called from the draw path. Null on the car surface, where there is no activity.
	 */
	@Nullable
	private View topWidgetsPanel;

	public HazardBannerLayer(@NonNull Context context) {
		super(context);
	}

	@Override
	public void setMapActivity(@Nullable MapActivity mapActivity) {
		super.setMapActivity(mapActivity);
		topWidgetsPanel = mapActivity != null ? mapActivity.findViewById(R.id.top_widgets_panel) : null;
	}

	@Override
	public void initLayer(@NonNull OsmandMapTileView view) {
		super.initLayer(view);
		float density = view.isCarView()
				? view.getCarViewDensity()
				: getContext().getResources().getDisplayMetrics().density;
		chipHeight = CHIP_HEIGHT_DP * density;
		chipPad = CHIP_PAD_DP * density;
		chipRadius = CHIP_RADIUS_DP * density;
		chipGap = CHIP_GAP_DP * density;
		topMargin = TOP_MARGIN_DP * density;

		chipPaint = new Paint();
		chipPaint.setAntiAlias(true);
		chipPaint.setStyle(Paint.Style.FILL);

		textPaint = new Paint();
		textPaint.setAntiAlias(true);
		textPaint.setTextAlign(Paint.Align.CENTER);
		textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
		textPaint.setTextSize(TEXT_SP * density);
		// Measurements are cached against the text, so a size change after init would leave stale
		// widths behind. Nothing changes it after this point - that is the contract, not luck.
	}

	/**
	 * Text-key to resource, by an explicit switch and not {@code Resources.getIdentifier}.
	 *
	 * <p>A lookup by name is a string the shrinker cannot see, so R8 is free to strip these three
	 * strings from a release build and the banner would render blank on the only build that ever
	 * reaches the car. A switch makes the reference real.
	 */
	@StringRes
	private static int resourceFor(@NonNull String textKey) {
		switch (textKey) {
			case "cairo_hazard_dust":
				return R.string.cairo_hazard_dust;
			case "cairo_hazard_low_visibility":
				return R.string.cairo_hazard_low_visibility;
			case "cairo_hazard_sun_glare":
				return R.string.cairo_hazard_sun_glare;
			default:
				return 0;
		}
	}

	@Override
	public void onDraw(Canvas canvas, RotatedTileBox tileBox, DrawSettings settings) {
		OsmandApplication app = getApplication();
		if (app == null || chipPaint == null) {
			return;
		}
		HazardBanner hazard = CairoDriveProviders.getHazard();
		String hazardText = null;
		int hazardBg = INFO_BG;
		int hazardFg = INFO_FG;
		if (hazard != null && hazard.severity != HazardBanner.SEVERITY_NONE
				&& !Algorithms.isEmpty(hazard.textKey)) {
			int resId = resourceFor(hazard.textKey);
			if (resId != 0) {
				hazardText = app.getString(resId);
				boolean warn = hazard.severity == HazardBanner.SEVERITY_WARN;
				hazardBg = warn ? WARN_BG : INFO_BG;
				hazardFg = warn ? WARN_FG : INFO_FG;
			}
		}

		String delayText = delayText(app);

		if (hazardText == null && delayText == null) {
			// The common case. Nothing measured, nothing allocated, nothing drawn.
			cachedHazardText = null;
			cachedDelayText = null;
			return;
		}

		float centerX = centerX(app, canvas);
		float y = topY(app) + topMargin;

		if (hazardText != null) {
			if (!hazardText.equals(cachedHazardText)) {
				cachedHazardText = hazardText;
				cachedHazardWidth = textPaint.measureText(hazardText);
			}
			drawChip(canvas, hazardText, cachedHazardWidth, centerX, y, hazardBg, hazardFg);
			y += chipHeight + chipGap;
		} else {
			cachedHazardText = null;
		}
		if (delayText != null) {
			if (!delayText.equals(cachedDelayText)) {
				cachedDelayText = delayText;
				cachedDelayWidth = textPaint.measureText(delayText);
			}
			drawChip(canvas, delayText, cachedDelayWidth, centerX, y, DELAY_BG, DELAY_FG);
		} else {
			cachedDelayText = null;
		}
	}

	/**
	 * "Traffic: +N min on your route", or null when there is nothing honest to say.
	 *
	 * <p>Derived from the time ALREADY on screen. {@code RoutingHelper.getLeftTime()} has run the
	 * traffic stretch through it before returning, so the delay is recovered by inverting that
	 * stretch rather than by recomputing a base - otherwise the chip and the arrival time beside it
	 * would be describing two different snapshots.
	 *
	 * <p><b>Throttled, and it has to be.</b> {@code getLeftTime()} is not a field read: it walks the
	 * remaining route segments and it emits CD_ETA. Calling it twenty times a second from the draw
	 * path would put a route walk inside {@code over} - the 61% bucket - to answer a question whose
	 * input, the TomTom flow snapshot, only changes every five minutes. Two seconds is far finer
	 * than the data it reports and costs nothing.
	 */
	@Nullable
	private String delayText(@NonNull OsmandApplication app) {
		RoutingHelper helper = app.getRoutingHelper();
		if (!helper.isRouteCalculated() || !helper.isFollowingMode()) {
			lastDelayText = null;
			lastDelayComputedMs = 0;
			return null;
		}
		long now = System.currentTimeMillis();
		// The lower bound catches a clock that jumped backwards, which would otherwise freeze the
		// chip at whatever it last said for as long as the offset lasted.
		long age = now - lastDelayComputedMs;
		if (lastDelayComputedMs != 0 && age >= 0 && age < DELAY_RECOMPUTE_MS) {
			return lastDelayText;
		}
		lastDelayComputedMs = now;
		long delay = TrafficAwareRouting.delayFromAdjustedSeconds(helper.getLeftTime());
		lastDelayText = delay < MIN_DELAY_SECONDS
				? null
				: app.getString(R.string.cairo_traffic_delay, (int) Math.round(delay / 60.0));
		return lastDelayText;
	}

	private void drawChip(@NonNull Canvas canvas, @NonNull String text, float textWidth,
	                      float centerX, float top, int bg, int fg) {
		float halfWidth = textWidth / 2f + chipPad;
		chipRect.set(centerX - halfWidth, top, centerX + halfWidth, top + chipHeight);
		chipPaint.setColor(bg);
		canvas.drawRoundRect(chipRect, chipRadius, chipRadius, chipPaint);
		// Centre on the CAP height rather than on the font's full line box: the Arabic strings
		// carry descenders the Latin ones do not, and centring on the line box would make the two
		// locales sit at visibly different heights inside an identical chip.
		textPaint.getTextBounds(text, 0, text.length(), textBounds);
		float baseline = chipRect.centerY() + textBounds.height() / 2f;
		textPaint.setColor(fg);
		canvas.drawText(text, centerX, baseline, textPaint);
	}

	/**
	 * Horizontal centre of the area the head unit actually shows, falling back to the canvas.
	 *
	 * <p>On a car screen the surface is wider than the visible region - the template's own panels
	 * sit over the edges - so centring on the canvas would push the chip under them.
	 */
	private float centerX(@NonNull OsmandApplication app, @NonNull Canvas canvas) {
		Rect visible = carVisibleArea(app);
		return visible != null ? visible.centerX() : canvas.getWidth() / 2f;
	}

	/**
	 * Where the chips may start: below whatever already owns the top of the screen.
	 *
	 * <p>On a car screen that is the visible region the template leaves us. On the phone it is the
	 * bottom of the top widgets panel - that panel is an Android View sitting OVER the map surface,
	 * so a chip drawn at y=0 would not overlap it untidily, it would be completely hidden behind it,
	 * which is the failure that looks like "the banner never appears".
	 */
	private float topY(@NonNull OsmandApplication app) {
		Rect visible = carVisibleArea(app);
		if (visible != null) {
			return visible.top;
		}
		View panel = topWidgetsPanel;
		return panel != null && panel.getVisibility() == View.VISIBLE ? panel.getBottom() : 0f;
	}

	@Nullable
	private Rect carVisibleArea(@NonNull OsmandApplication app) {
		if (view == null || !view.isCarView()) {
			return null;
		}
		NavigationSession session = app.getCarNavigationSession();
		if (session == null) {
			return null;
		}
		SurfaceRenderer renderer = session.getNavigationCarSurface();
		if (renderer == null) {
			return null;
		}
		Rect visible = renderer.getVisibleArea();
		return visible != null && !visible.isEmpty() ? visible : null;
	}

	/**
	 * True: the chips are screen furniture, not map content. Returning false would rotate them with
	 * the map, which during navigation means upside-down text most of the time.
	 */
	@Override
	public boolean drawInScreenPixels() {
		return true;
	}
}
