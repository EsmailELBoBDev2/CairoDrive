package net.osmand.plus.cairodrive.providers;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.PlatformUtil;
import net.osmand.data.LatLon;
import net.osmand.plus.BuildConfig;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.cairodrive.CairoDriveLogger;
import net.osmand.util.MapUtils;

import org.apache.commons.logging.Log;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Egypt's EV charging network, bundled in the APK - no key, no quota, no network.
 *
 * <h3>Why this is data and not an API</h3>
 *
 * The whole Egyptian dataset is under 500 stations, which is small enough to ship. That turns
 * every question about the provider into a non-question: there is no key to manage, no quota to
 * raise before shipping, no rate limit (Open Charge Map's could not be verified at all - their
 * site was unreachable from the environment that did the audit), no per-tap billing, and no
 * failure mode where the feature stops working because the connection dropped.
 *
 * <p>That last point is the real argument. A driver looks for a charger when they are somewhere
 * unfamiliar and getting low, which correlates almost exactly with the places Cairo's mobile
 * coverage is worst. A live API would be least available at the moment it was most needed.
 *
 * <p>The cost is staleness: the bundle is a snapshot and only changes when the app is rebuilt.
 * For a network that adds a handful of sites a year that is an acceptable trade, and
 * {@code tools/cd-build-ocm-bundle.py} regenerates it in one command.
 *
 * <h3>Loading</h3>
 *
 * Parsed lazily on first use, off the main thread by the caller's choice of thread, and held for
 * the process lifetime - 485 small objects, a few hundred kilobytes. Read straight from
 * {@code AssetManager} and deliberately NOT registered in {@code bundled_assets.json}: that
 * manifest drives {@code CheckAssetsTask.unpackBundledAssets}, which has no per-entry catch, so
 * every entry added to it is another chance to abort the whole extraction and skip the
 * {@code PREVIOUS_INSTALLED_VERSION.set()} after it. Nothing needs this file on disk.
 */
public final class EvChargingBundle {

	private static final Log LOG = PlatformUtil.getLog(EvChargingBundle.class);
	private static final String TAG = "CD_EV";
	private static final String ASSET = "cairodrive_ev_eg.json";

	private static volatile List<Station> stations;
	private static volatile boolean loadFailed;

	/** One charging site. Immutable; shared across threads without copying. */
	public static final class Station {
		public final LatLon location;
		public final String name;
		public final String address;
		public final String operator;
		/**
		 * True when OCM classes access as membership-required.
		 *
		 * <p>65% of Egyptian sites are, so this is not an edge case - it is the majority, and a UI
		 * that implies walk-up access would be wrong more often than right. Precomputed in the
		 * bundle rather than inferred from the usage string here, so there is one place to correct
		 * if OCM's vocabulary changes.
		 */
		public final boolean membershipRequired;
		public final int points;
		public final String connectors;
		public final double maxPowerKw;

		Station(LatLon location, String name, String address, String operator,
		        boolean membershipRequired, int points, String connectors, double maxPowerKw) {
			this.location = location;
			this.name = name;
			this.address = address;
			this.operator = operator;
			this.membershipRequired = membershipRequired;
			this.points = points;
			this.connectors = connectors;
			this.maxPowerKw = maxPowerKw;
		}
	}

	private EvChargingBundle() {
	}

	public static boolean isEnabled() {
		return BuildConfig.CAIRODRIVE_OPEN_CHARGE_MAP;
	}

	/**
	 * The nearest stations to a point, closest first.
	 *
	 * <p>A linear scan over 485 records, which is roughly 485 haversine evaluations - tens of
	 * microseconds. A spatial index would be the right answer at a hundred times this size and is
	 * pure overhead at this one: it would add a structure to build, keep correct and test, to save
	 * time that is already below the threshold of anything a driver can perceive.
	 *
	 * <p>Returns an empty list rather than null when the feature is off or the bundle is missing,
	 * so a caller cannot forget to check.
	 */
	@NonNull
	public static List<Station> nearest(@Nullable OsmandApplication app, @Nullable LatLon around,
	                                    int limit, double maxDistanceM) {
		if (around == null || limit <= 0) {
			return Collections.emptyList();
		}
		List<Station> all = load(app);
		if (all.isEmpty()) {
			return Collections.emptyList();
		}
		List<Station> within = new ArrayList<>();
		for (Station station : all) {
			if (MapUtils.getDistance(station.location, around) <= maxDistanceM) {
				within.add(station);
			}
		}
		final double lat = around.getLatitude();
		final double lon = around.getLongitude();
		Collections.sort(within, new Comparator<Station>() {
			@Override
			public int compare(Station a, Station b) {
				double da = MapUtils.getDistance(a.location, lat, lon);
				double db = MapUtils.getDistance(b.location, lat, lon);
				return Double.compare(da, db);
			}
		});
		return within.size() <= limit ? within : new ArrayList<>(within.subList(0, limit));
	}

	/**
	 * Parses the asset once.
	 *
	 * <p>A failure is latched in {@link #loadFailed} rather than retried. If the asset is missing
	 * or malformed it will be missing or malformed every time, and retrying would mean re-parsing
	 * on every call from the draw or search path for a result that cannot change.
	 */
	@NonNull
	public static List<Station> load(@Nullable OsmandApplication app) {
		List<Station> cached = stations;
		if (cached != null) {
			return cached;
		}
		if (loadFailed || app == null || !isEnabled()) {
			return Collections.emptyList();
		}
		synchronized (EvChargingBundle.class) {
			if (stations != null) {
				return stations;
			}
			long start = System.currentTimeMillis();
			try {
				List<Station> parsed = parse(readAsset(app));
				stations = Collections.unmodifiableList(parsed);
				log("loaded n=" + parsed.size() + " ms=" + (System.currentTimeMillis() - start));
				return stations;
			} catch (Throwable t) {
				loadFailed = true;
				LOG.error("EV bundle load failed", t);
				log("load FAILED " + t.getClass().getSimpleName());
				return Collections.emptyList();
			}
		}
	}

	@NonNull
	private static String readAsset(@NonNull OsmandApplication app) throws Exception {
		InputStream in = app.getAssets().open(ASSET);
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream(160 * 1024);
			byte[] buffer = new byte[8192];
			int read;
			while ((read = in.read(buffer)) > 0) {
				out.write(buffer, 0, read);
			}
			return out.toString("UTF-8");
		} finally {
			try {
				in.close();
			} catch (Exception ignored) {
			}
		}
	}

	@NonNull
	private static List<Station> parse(@NonNull String json) throws Exception {
		JSONArray array = new JSONObject(json).getJSONArray("stations");
		List<Station> out = new ArrayList<>(array.length());
		for (int i = 0; i < array.length(); i++) {
			JSONObject o = array.optJSONObject(i);
			if (o == null || !o.has("lat") || !o.has("lon")) {
				continue;
			}
			StringBuilder connectors = new StringBuilder();
			double maxKw = 0;
			JSONArray conn = o.optJSONArray("conn");
			if (conn != null) {
				for (int c = 0; c < conn.length(); c++) {
					JSONObject one = conn.optJSONObject(c);
					if (one == null) {
						continue;
					}
					String type = one.optString("t", null);
					double kw = one.optDouble("kw", 0);
					if (kw > maxKw) {
						maxKw = kw;
					}
					if (type != null && !type.isEmpty()) {
						if (connectors.length() > 0) {
							connectors.append(", ");
						}
						connectors.append(type);
					}
				}
			}
			out.add(new Station(
					new LatLon(o.optDouble("lat"), o.optDouble("lon")),
					o.optString("name", null),
					o.optString("addr", null),
					o.optString("op", null),
					o.optInt("member", 0) == 1,
					o.optInt("pts", 0),
					connectors.toString(),
					maxKw));
		}
		return out;
	}

	private static void log(String message) {
		if (CairoDriveLogger.isEnabled()) {
			CairoDriveLogger.getInstance().log(TAG, message);
		}
	}
}
