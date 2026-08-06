package net.osmand.plus.onlinerouting.engine;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;
import net.osmand.data.LatLon;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.onlinerouting.EngineParameter;
import net.osmand.plus.onlinerouting.VehicleType;
import net.osmand.plus.routing.RouteDirectionInfo;
import net.osmand.router.RouteCalculationProgress;
import net.osmand.router.TurnType;
import net.osmand.shared.gpx.GpxFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static net.osmand.plus.onlinerouting.engine.EngineType.GEOAPIFY_TYPE;
import static net.osmand.util.Algorithms.isEmpty;

/**
 * Geoapify's Routing API.
 *
 * <p>Written because Geoapify returns GeoJSON that none of the existing engines can read. It
 * looks close enough to {@link OrsEngine} to tempt reuse and is not: three differences would each
 * produce a silently wrong route rather than an error, which is why this is a separate class.
 *
 * <ol>
 *   <li><b>The geometry is a MultiLineString, not a LineString.</b> ORS gives
 *       {@code coordinates[i] = [lon, lat]}; Geoapify gives {@code coordinates[i][j] = [lon, lat]},
 *       one array per leg. Read with the ORS parser the first coordinate pair would be an array
 *       rather than a number.</li>
 *   <li><b>Steps hang off {@code legs}, not {@code segments}</b>, and a step carries
 *       {@code from_index}/{@code to_index} where ORS carries a two-element {@code way_points}
 *       array.</li>
 *   <li><b>{@code instruction} is an object with a {@code text} field</b>, not a string, and the
 *       duration field is {@code time}, not {@code duration}.</li>
 * </ol>
 *
 * <p>The step indices are into the FLATTENED coordinate list, so the flattening below has to
 * happen in the same order the server used or every turn lands at the wrong place on the map.
 *
 * <p>Turn types are text, not the integer enum ORS uses - Geoapify sends {@code turn_type} like
 * "Left" or "Slight right". Anything unrecognised becomes a plain "continue", which degrades to a
 * route that is geometrically right with vaguer prompts rather than to a wrong one.
 */
public class GeoapifyEngine extends JsonOnlineRoutingEngine {

	public GeoapifyEngine(@Nullable Map<String, String> params) {
		super(params);
	}

	@NonNull
	@Override
	public OnlineRoutingEngine getType() {
		return GEOAPIFY_TYPE;
	}

	@Override
	@NonNull
	public String getTitle() {
		return "Geoapify";
	}

	@NonNull
	@Override
	public String getTypeName() {
		return "GEOAPIFY";
	}

	@NonNull
	@Override
	public String getStandardUrl() {
		return "https://api.geoapify.com/v1/routing";
	}

	@Override
	protected void collectAllowedParameters(@NonNull Set<EngineParameter> params) {
		params.add(EngineParameter.KEY);
		params.add(EngineParameter.VEHICLE_KEY);
		params.add(EngineParameter.CUSTOM_NAME);
		params.add(EngineParameter.NAME_INDEX);
		params.add(EngineParameter.CUSTOM_URL);
		params.add(EngineParameter.API_KEY);
	}

	@Override
	public OnlineRoutingEngine newInstance(Map<String, String> params) {
		return new GeoapifyEngine(params);
	}

	@Override
	protected void collectAllowedVehicles(@NonNull List<VehicleType> vehicles) {
		vehicles.add(new VehicleType("drive", R.string.routing_engine_vehicle_type_car));
		vehicles.add(new VehicleType("truck", R.string.routing_engine_vehicle_type_hgv));
		vehicles.add(new VehicleType("bicycle", R.string.routing_engine_vehicle_type_bike));
		vehicles.add(new VehicleType("walk", R.string.routing_engine_vehicle_type_walking));
		vehicles.add(new VehicleType("hike", R.string.routing_engine_vehicle_type_hiking));
	}

	@Override
	protected void makeFullUrl(@NonNull StringBuilder sb, @NonNull List<LatLon> path,
	                           @Nullable Float startBearing) {
		sb.append('?').append("waypoints=");
		for (int i = 0; i < path.size(); i++) {
			LatLon point = path.get(i);
			if (i > 0) {
				// A literal '|' would be fine for most servers but is not legal in a URL, and
				// this string goes through java.net.URL unencoded.
				sb.append("%7C");
			}
			sb.append(point.getLatitude()).append(',').append(point.getLongitude());
		}
		String vehicle = getVehicleKeyForUrl();
		sb.append('&').append("mode=").append(isEmpty(vehicle) ? "drive" : vehicle);
		String apiKey = get(EngineParameter.API_KEY);
		if (!isEmpty(apiKey)) {
			sb.append('&').append("apiKey=").append(apiKey);
		}
	}

	@Override
	public OnlineRoutingResponse responseByGpxFile(@NonNull OsmandApplication app,
	                                               @NonNull GpxFile gpxFile,
	                                               boolean initialCalculation,
	                                               @Nullable RouteCalculationProgress calculationProgress) {
		return null;
	}

	@Nullable
	@Override
	public OnlineRoutingResponse parseServerResponse(@NonNull JSONObject root,
	                                                 @NonNull OsmandApplication app,
	                                                 boolean leftSideNavigation)
			throws JSONException {
		// MultiLineString: one coordinate array per leg. Flattened in server order because the
		// step indices below address this list.
		JSONArray lines = root.getJSONObject("geometry").getJSONArray("coordinates");
		List<LatLon> points = new ArrayList<>();
		for (int i = 0; i < lines.length(); i++) {
			JSONArray line = lines.getJSONArray(i);
			for (int j = 0; j < line.length(); j++) {
				JSONArray point = line.getJSONArray(j);
				points.add(new LatLon(point.getDouble(1), point.getDouble(0)));
			}
		}
		if (isEmpty(points)) {
			return null;
		}

		List<RouteDirectionInfo> directions = new ArrayList<>();
		JSONArray legs = root.getJSONObject("properties").getJSONArray("legs");
		for (int i = 0; i < legs.length(); i++) {
			JSONArray steps = legs.getJSONObject(i).optJSONArray("steps");
			if (steps == null) {
				continue;
			}
			for (int j = 0; j < steps.length(); j++) {
				JSONObject step = steps.getJSONObject(j);
				double distance = step.optDouble("distance", 0);
				double time = step.optDouble("time", 0);
				// Guard the division: a zero-length step is legal in the response and would
				// otherwise produce an infinite or NaN speed, which corrupts the ETA for the
				// whole route rather than just that step.
				float averageSpeed = time > 0 ? (float) (distance / time) : 0f;

				JSONObject instruction = step.optJSONObject("instruction");
				String text = instruction != null ? instruction.optString("text", "") : "";

				TurnType turnType = getTurnType(step.optString("turn_type", ""), leftSideNavigation);
				RouteDirectionInfo direction = new RouteDirectionInfo(averageSpeed, turnType);
				direction.routePointOffset = step.optInt("from_index", 0);
				direction.setDescriptionRoute(text);
				direction.setStreetName(step.optString("name", ""));
				direction.setDistance((int) Math.round(distance));
				directions.add(direction);
			}
		}

		List<Location> route = convertRouteToLocationsList(points);
		return new OnlineRoutingResponse(route, directions);
	}

	/**
	 * Geoapify sends a human-readable turn name rather than ORS's integer enum.
	 * Documented at https://apidocs.geoapify.com/docs/routing/ under "turn_type".
	 */
	@NonNull
	private TurnType getTurnType(@NonNull String turnType, boolean leftSide) {
		switch (turnType) {
			case "Left":
				return TurnType.fromString("TL", leftSide);
			case "Right":
				return TurnType.fromString("TR", leftSide);
			case "Sharp left":
				return TurnType.fromString("TSHL", leftSide);
			case "Sharp right":
				return TurnType.fromString("TSHR", leftSide);
			case "Slight left":
				return TurnType.fromString("TSLL", leftSide);
			case "Slight right":
				return TurnType.fromString("TSLR", leftSide);
			case "Roundabout":
			case "Roundabout enter":
				return TurnType.fromString("RNDB", leftSide);
			case "Roundabout exit":
				return TurnType.fromString(leftSide ? "TL" : "TR", leftSide);
			case "Uturn":
			case "U-turn":
				return TurnType.fromString("TU", leftSide);
			case "Keep left":
				return TurnType.fromString("KL", leftSide);
			case "Keep right":
				return TurnType.fromString("KR", leftSide);
			default:
				// Includes "Straight", "Start", "End" and anything Geoapify adds later. A vague
				// prompt on a correct road beats a confident wrong one.
				return TurnType.fromString("C", leftSide);
		}
	}

	@NonNull
	@Override
	protected String getErrorMessageKey() {
		return "message";
	}

	@NonNull
	@Override
	protected String getRootArrayKey() {
		return "features";
	}
}
