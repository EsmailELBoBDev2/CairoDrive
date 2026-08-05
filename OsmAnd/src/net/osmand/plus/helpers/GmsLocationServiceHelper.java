package net.osmand.plus.helpers;

import static android.content.Context.LOCATION_SERVICE;

import android.location.Location;
import android.location.LocationManager;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.location.LocationManagerCompat;
import androidx.core.location.LocationRequestCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationAvailability;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.Task;

import net.osmand.PlatformUtil;
import net.osmand.plus.OsmandApplication;

import org.apache.commons.logging.Log;

import java.util.Collections;
import java.util.List;

public class GmsLocationServiceHelper extends LocationServiceHelper {

	private static final Log LOG = PlatformUtil.getLog(GmsLocationServiceHelper.class);

	// FusedLocationProviderClient - Main class for receiving location updates.
	private final FusedLocationProviderClient fusedLocationProviderClient;

	// LocationRequest - Requirements for the location updates, i.e., how often you should receive
	// updates, the priority, etc.
	private final LocationRequest fusedLocationRequest;
	private final LocationRequestCompat networkLocationRequest;

	// LocationCallback - Called when FusedLocationProviderClient has a new Location.
	private final com.google.android.gms.location.LocationCallback fusedLocationCallback;

	private LocationCallback locationCallback;

	public GmsLocationServiceHelper(@NonNull OsmandApplication app) {
		super(app);

		fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(app);
		// N4, in its ORIGINAL wording: "raise the GNSS fix rate".
		//
		// There was nothing to raise - PRIORITY_HIGH_ACCURACY at a 100 ms interval is already ten
		// times what the hardware delivers - which is why N4 became position prediction instead.
		// But two knobs were still left on the table by the bare Builder, and leaving them unset
		// while claiming "already maximal" was not quite true:
		//
		//   setMinUpdateIntervalMillis(0) - without this the minimum defaults to the interval, so
		//   the fused provider will HOLD a fix that arrives early rather than deliver it. On a
		//   phone whose GNSS bursts, that is a real fix arriving late for no reason.
		//
		//   setWaitForAccurateLocation(false) - stops the first fix being delayed while the
		//   provider tries to improve it. Matters at the start of a drive, which is exactly when
		//   the driver is waiting to be told where to go.
		//
		// Neither will move the steady-state rate, and neither is a substitute for the prediction.
		// They close the gap between "we ask for everything" and "we actually ask for everything".
		fusedLocationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 100)
				.setMinUpdateIntervalMillis(0)
				.setWaitForAccurateLocation(false)
				.build();
		networkLocationRequest = new LocationRequestCompat.Builder(5000)
				.setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
				.setMinUpdateIntervalMillis(500)
				.build();
		fusedLocationCallback = new com.google.android.gms.location.LocationCallback() {
			@Override
			public void onLocationResult(@NonNull LocationResult locationResult) {
				LocationCallback locationCallback = GmsLocationServiceHelper.this.locationCallback;
				if (locationCallback != null) {
					Location location = locationResult.getLastLocation();
					net.osmand.Location l = convertLocation(location);
					locationCallback.onLocationResult(l == null ? Collections.emptyList() : Collections.singletonList(l));
				}
			}

			@Override
			public void onLocationAvailability(@NonNull LocationAvailability locationAvailability) {
				LocationCallback locationCallback = GmsLocationServiceHelper.this.locationCallback;
				if (locationCallback != null) {
					locationCallback.onLocationAvailability(locationAvailability.isLocationAvailable());
				}
			}
		};
	}

	@Override
	public void requestLocationUpdates(@NonNull LocationCallback locationCallback) {
		this.locationCallback = locationCallback;
		// request location updates
		try {
			fusedLocationProviderClient.requestLocationUpdates(
					fusedLocationRequest, fusedLocationCallback, Looper.myLooper());
		} catch (SecurityException e) {
			LOG.debug("Location service permission not granted", e);
			throw e;
		} catch (IllegalArgumentException e) {
			LOG.debug("GPS location provider not available", e);
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
		List<String> providers = locationManager.getProviders(true);
		for (String provider : providers) {
			// PASSIVE_PROVIDER is excluded here for the same reason AndroidApiLocationServiceHelper
			// excludes it (IGNORED_NETWORK_PROVIDERS): subscribing to it wakes this app on every
			// OTHER app's location fix, for no fix of our own. The two helpers had drifted - the
			// AOSP one listed all three providers, this one listed two - and the Play build uses
			// this one, so the phone that actually drives was the one paying for it.
			if (provider == null
					|| provider.equals(LocationManager.GPS_PROVIDER)
					|| provider.equals(LocationManager.PASSIVE_PROVIDER)
					|| provider.equals(LocationManager.FUSED_PROVIDER)) {
				continue;
			}
			try {
				NetworkListener networkListener = new NetworkListener(provider);
				LocationManagerCompat.requestLocationUpdates(locationManager, provider, networkLocationRequest,
						networkListener, Looper.myLooper());
				networkListeners.add(networkListener);
			} catch (SecurityException e) {
				LOG.debug(provider + " location service permission not granted", e);
			} catch (IllegalArgumentException e) {
				LOG.debug(provider + " location provider not available", e);
			}
		}
	}

	@Override
	public void removeLocationUpdates() {
		// remove location updates
		try {
			fusedLocationProviderClient.removeLocationUpdates(fusedLocationCallback);
		} catch (SecurityException e) {
			LOG.debug("Location service permission not granted", e);
			throw e;
		} finally {
			removeNetworkLocationUpdates();
		}
	}

	@Nullable
	public net.osmand.Location getFirstTimeRunDefaultLocation(@Nullable LocationCallback locationCallback) {
		if (locationCallback == null) {
			return null;
		}
		try {
			Task<Location> lastLocation = fusedLocationProviderClient.getLastLocation();
			lastLocation.addOnSuccessListener(loc -> locationCallback.onLocationResult(loc != null
					? Collections.singletonList(convertLocation(loc)) : Collections.emptyList() ));
		} catch (SecurityException e) {
			LOG.debug("Location service permission not granted", e);
		} catch (IllegalArgumentException e) {
			LOG.debug("GPS location provider not available", e);
		}
		return null;
	}
}
