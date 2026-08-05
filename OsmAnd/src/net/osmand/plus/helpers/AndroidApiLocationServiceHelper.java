package net.osmand.plus.helpers;

import static android.content.Context.LOCATION_SERVICE;
import static android.location.LocationManager.FUSED_PROVIDER;
import static android.location.LocationManager.GPS_PROVIDER;
import static android.location.LocationManager.PASSIVE_PROVIDER;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.location.LocationListenerCompat;

import net.osmand.PlatformUtil;
import net.osmand.plus.OsmandApplication;

import org.apache.commons.logging.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class AndroidApiLocationServiceHelper extends LocationServiceHelper implements LocationListenerCompat {

	private static final Log LOG = PlatformUtil.getLog(AndroidApiLocationServiceHelper.class);

	@SuppressLint("InlinedApi")
	private static final List<String> IGNORED_NETWORK_PROVIDERS = Arrays.asList(GPS_PROVIDER, PASSIVE_PROVIDER, FUSED_PROVIDER);

	private LocationCallback locationCallback;

	public AndroidApiLocationServiceHelper(@NonNull OsmandApplication app) {
		super(app);
	}

	@NonNull
	protected String getPrimaryProvider() {
		return GPS_PROVIDER;
	}

	@NonNull
	protected List<String> getIgnoredNetworkProviders() {
		return IGNORED_NETWORK_PROVIDERS;
	}

	@Override
	public void requestLocationUpdates(@NonNull LocationCallback locationCallback) {
		this.locationCallback = locationCallback;
		// request location updates
		requestLocationUpdatesImpl(this, getPrimaryProvider());
	}

	protected boolean requestLocationUpdatesImpl(@NonNull LocationListener listener, @NonNull String provider) {
		LocationManager locationManager = (LocationManager) app.getSystemService(LOCATION_SERVICE);
		try {
			// N4. The legacy 4-argument call below already asks for minTime=0 and minDistance=0 -
			// "give me everything" - and that was the basis for concluding there was nothing to
			// raise. It is not the whole story: the legacy form leaves two things to the
			// platform's discretion that Android 12 lets an app state outright.
			//
			//   setMaxUpdateDelayMillis(0)  forbids BATCHING. With a non-zero delay the platform
			//                               may buffer fixes in the GNSS hardware and deliver them
			//                               in a burst, which preserves the fix RATE while
			//                               destroying the LATENCY - and latency is the whole
			//                               point here. A burst of five one-second-old fixes is
			//                               useless to a turn prompt.
			//   QUALITY_HIGH_ACCURACY       asks the platform not to quietly downgrade the request
			//                               under battery pressure.
			//
			// Neither raises the ceiling; both stop it being lowered underneath us. CD_FIXRATE
			// carries the delivered hz, which is what says whether this changed anything on the
			// POCO C85 - the honest answer being that it may well not, and the log will say so.
			// Falls back to the legacy call on ANY failure other than a permission one. A vendor
			// that rejects the new overload must not cost the app its location entirely - losing
			// GPS is infinitely worse than losing the batching guarantee this is asking for.
			// SecurityException is deliberately not caught here: that is a missing permission, it
			// would fail identically on the legacy path, and swallowing it would hide it.
			if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
				try {
					android.location.LocationRequest request =
							new android.location.LocationRequest.Builder(0)
									.setQuality(android.location.LocationRequest.QUALITY_HIGH_ACCURACY)
									.setMinUpdateIntervalMillis(0)
									.setMaxUpdateDelayMillis(0)
									.build();
					locationManager.requestLocationUpdates(provider, request,
							androidx.core.content.ContextCompat.getMainExecutor(app), listener);
					return true;
				} catch (SecurityException e) {
					throw e;
				} catch (Throwable t) {
					LOG.info("LocationRequest path unavailable on " + provider
							+ ", falling back to the legacy call", t);
				}
			}
			locationManager.requestLocationUpdates(provider, 0, 0, listener);
			return true;
		} catch (SecurityException e) {
			LOG.debug(provider + " location service permission not granted", e);
			throw e;
		} catch (IllegalArgumentException e) {
			LOG.debug(provider + " location provider not available", e);
			throw e;
		}
	}

	@Override
	public boolean isNetworkLocationUpdatesSupported() {
		return true;
	}

	@Override
	public void requestNetworkLocationUpdates(@NonNull LocationCallback locationCallback) {
		this.networkLocationCallback = locationCallback;
		// request location updates
		LocationManager locationManager = (LocationManager) app.getSystemService(LOCATION_SERVICE);

		List<String> ignoredProviders = getIgnoredNetworkProviders();
		List<String> providers = locationManager.getProviders(true);
		for (String provider : providers) {
			if (provider == null || ignoredProviders.contains(provider)) {
				continue;
			}
			try {
				NetworkListener networkListener = new NetworkListener(provider);
				if (requestLocationUpdatesImpl(networkListener, provider)) {
					networkListeners.add(networkListener);
				}
			} catch (Exception ignored) {

			}
		}
	}

	@Override
	public void removeLocationUpdates() {
		// remove location updates
		LocationManager locationManager = (LocationManager) app.getSystemService(LOCATION_SERVICE);
		try {
			locationManager.removeUpdates(this);
		} catch (SecurityException e) {
			LOG.debug("Location service permission not granted", e);
			throw e;
		} finally {
			removeNetworkLocationUpdates();
		}
	}

	@Nullable
	public net.osmand.Location getFirstTimeRunDefaultLocation(@Nullable LocationCallback locationCallback) {
		LocationManager locationManager = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
		List<String> providers = new ArrayList<>(locationManager.getProviders(true));
		// note, passive provider is from API_LEVEL 8 but it is a constant, we can check for it.
		// constant should not be changed in future
		int passiveFirst = providers.indexOf(PASSIVE_PROVIDER);
		// put passive provider to first place
		if (passiveFirst > -1) {
			providers.add(0, providers.remove(passiveFirst));
		}
		// find location
		for (String provider : providers) {
			try {
				net.osmand.Location location = convertLocation(locationManager.getLastKnownLocation(provider));
				if (location != null) {
					return location;
				}
			} catch (SecurityException e) {
				LOG.debug(provider + " location service permission not granted", e);
			} catch (IllegalArgumentException e) {
				LOG.debug(provider + " location provider not available", e);
			}
		}
		return null;
	}

	@Override
	public void onLocationChanged(@NonNull Location location) {
		LocationCallback locationCallback = this.locationCallback;
		if (locationCallback != null) {
			net.osmand.Location l = convertLocation(location);
			locationCallback.onLocationResult(l == null ? Collections.emptyList() : Collections.singletonList(l));
		}
	}

	@Override
	public void onProviderEnabled(@NonNull String provider) {
		LocationCallback locationCallback = this.locationCallback;
		if (locationCallback != null) {
			locationCallback.onLocationAvailability(true);
		}
	}

	@Override
	public void onProviderDisabled(@NonNull String provider) {
		LocationCallback locationCallback = this.locationCallback;
		if (locationCallback != null) {
			locationCallback.onLocationAvailability(false);
		}
	}
}
