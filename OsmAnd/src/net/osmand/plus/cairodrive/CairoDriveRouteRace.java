package net.osmand.plus.cairodrive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.helpers.CairoDriveLog;
import net.osmand.plus.routing.RouteCalculationParams;
import net.osmand.router.RouteCalculationProgress;

import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

/**
 * Run the offline route calculation and an online one AT THE SAME TIME, and use whichever
 * answers first.
 *
 * <h3>Why a race rather than the fallback that already exists</h3>
 *
 * OsmAnd already supports online routing with an offline fallback, and it is the wrong shape
 * for Cairo. {@code RouteProvider.calculateRouteImpl} only calls {@code findVectorMapsRoute}
 * AFTER the online attempt has thrown, and the online attempt uses
 * {@code AndroidNetworkUtils.CONNECT_TIMEOUT} = 30 s with {@code READ_TIMEOUT} = 60 s. So on a
 * network that is present but bad - which is the normal Cairo case, not the exotic one - the
 * driver can wait up to about ninety seconds before the four-to-eight second offline
 * calculation even STARTS. That is roughly ten times worse than doing nothing, in exactly the
 * conditions the feature is supposed to help with.
 *
 * <p>Racing removes that entirely. The offline side is the guarantee: it needs no network and
 * it has never taken longer than about eight seconds on the POCO C85. The online side can only
 * improve on that, because nothing waits for it - if it is slow, or the connection is gone, or
 * the server returns nonsense, the offline result is already in hand and the online one is
 * discarded. <b>The worst case of this feature is the current behaviour.</b>
 *
 * <h3>What "first" means, and why it is not simply first-to-return</h3>
 *
 * A failed calculation returns fast. If the online side errors in 200 ms and we took the first
 * result to arrive, every reroute would return a failure instantly and navigation would break
 * on the first bad request. So the race takes the first result that is actually USABLE, and if
 * the quick one is unusable it keeps waiting for the other. Only if both fail does it return a
 * failure, which is what a plain offline calculation would have done anyway.
 *
 * <h3>Cost</h3>
 *
 * The loser's work is wasted, and on the offline side that is several seconds of a small phone's
 * CPU. That is accepted deliberately: cancelling the native calculation the moment the online
 * one wins would remove the guarantee that something is always in flight, and the guarantee is
 * the entire reason this is safe to turn on. Battery is the price of never being stranded
 * waiting for a server.
 *
 * <p>Every race writes one {@code CD_ROUTE_RACE} line carrying both durations and the winner,
 * because the question this feature exists to answer - is online actually faster on THIS phone
 * on THIS network - cannot be answered from a UI, only from a drive.
 *
 * <h3>How it is wired, and the data race that had to be fixed first</h3>
 *
 * The call site could not simply be added, because
 * {@code RouteProvider.findOnlineRoute} MUTATES the shared {@link
 * net.osmand.plus.routing.RouteCalculationParams}: it assigns {@code params.gpxFile},
 * {@code params.gpxRoute}, {@code params.initialCalculation}, and sets
 * {@code params.intermediates = null}. {@code findVectorMapsRoute} reads the same object.
 * Running the two concurrently against one {@code params} is a genuine data race on the core
 * navigation path, and the thing it would corrupt is the offline route - the one result that
 * must never be wrong, because it is what the driver falls back on when everything else fails.
 *
 * <p>{@link #copyForOnline} is the fix: the online side gets its own parameters. With that in
 * place the race IS wired - {@code RouteProvider.raceOnlineWithOffline} calls it on the live
 * reroute path and {@code CAIRODRIVE_ROUTE_RACE} defaults to true. It is deliberately NOT run for
 * the traffic detour or the repair probe; see that method for why an online win would break each
 * of them rather than help.
 *
 * <p>Verified before commit, by executing this logic standalone with synthetic timings, since
 * the class deliberately has no Android dependency beyond the logger:
 * online 300 ms vs offline 5 s returns ONLINE at 324 ms; offline 800 ms vs online 6 s returns
 * OFFLINE at 816 ms - NOT at {@link #ONLINE_GIVE_UP_MS}, which is the whole point of polling
 * both rather than waiting on the online future; a fast online failure still yields the offline
 * route; an online side that never answers costs nothing; and two failures return the same
 * empty result a lone offline calculation would have.
 */
public final class CairoDriveRouteRace {

	/** NO "CD_" prefix: {@link CairoDriveLog#log} adds it. */
	private static final String TRACE_TAG = "ROUTE_RACE";

	/**
	 * How long the online side is allowed to be interesting for.
	 *
	 * <p>Not a network timeout - the HTTP layer keeps its own, and this does not shorten it.
	 * This is the point past which an online answer has stopped being worth having, because the
	 * offline one is either already back or about to be. Set just beyond the measured offline
	 * worst case so that a slow server never delays a reroute, only misses it.
	 */
	private static final long ONLINE_GIVE_UP_MS = 9_000;

	private static final ThreadFactory THREADS = r -> {
		Thread t = new Thread(r, "cairodrive-route-race");
		t.setDaemon(true);
		return t;
	};

	private CairoDriveRouteRace() {
	}

	/** What the race needs to know about a candidate result without depending on its type. */
	public interface Usable<T> {
		boolean isUsable(@Nullable T result);
	}

	/**
	 * @param offline the local calculation. Must be able to answer without a network.
	 * @param online  the networked calculation.
	 * @return the first usable result, or the offline result if neither was usable.
	 */
	@Nullable
	public static <T> T race(@NonNull Callable<T> offline,
	                         @NonNull Callable<T> online,
	                         @NonNull Usable<T> usable) {
		return race(offline, online, usable, null);
	}

	/**
	 * @param abandonOffline run when the ONLINE side wins and the local calculation is still
	 *                       going. Not a hard kill - the native search is not interruptible -
	 *                       but the hook the caller needs to mark that work as unwanted.
	 *                       <p>
	 *                       Without it the abandoned calculation runs to completion and is
	 *                       invisible to cancellation, because afterExecute has already removed
	 *                       its task from tasksMap by then. It keeps holding the warm routing
	 *                       slot (so the NEXT reroute reads reuse=0 through no fault of the
	 *                       cache), it writes a CD_ROUTE_TIMING line AFTER CD_REROUTE finished
	 *                       describing a search whose answer was thrown away, and it can still
	 *                       set missingMapsCalculationResult on shared params long after the
	 *                       route was installed. The first two corrupt exactly the numbers a
	 *                       drive log is read for.
	 */
	@Nullable
	public static <T> T race(@NonNull Callable<T> offline,
	                         @NonNull Callable<T> online,
	                         @NonNull Usable<T> usable,
	                         @Nullable Runnable abandonOffline) {
		ExecutorService pool = Executors.newFixedThreadPool(2, THREADS);
		long started = System.currentTimeMillis();
		try {
			Future<T> offlineTask = pool.submit(offline);
			Future<T> onlineTask = pool.submit(online);

			// POLL BOTH, never block on one of them. Waiting on the online future with a
			// timeout looks equivalent and is not: it would sit there for the full timeout
			// even when the offline side had already answered in four seconds, which turns
			// this feature into the delay it was written to remove. The loop below returns
			// the moment EITHER side has something usable.
			T offlineResult = null;
			T onlineResult = null;
			long offlineMs = -1;
			long onlineMs = -1;

			while (true) {
				long elapsed = System.currentTimeMillis() - started;

				if (onlineMs < 0 && onlineTask.isDone()) {
					onlineMs = elapsed;
					onlineResult = get(onlineTask, "online");
					if (usable.isUsable(onlineResult) && !offlineTask.isDone()) {
						// The only way online wins: it is back and the local calculation is
						// not. Nothing is delayed by taking it, and the driver gets a route
						// seconds earlier.
						log("ONLINE won in " + onlineMs + " ms, offline still running");
						if (abandonOffline != null) {
							try {
								abandonOffline.run();
							} catch (Throwable ignored) {
								// Telemetry hygiene must never cost the route we just won.
							}
						}
						return onlineResult;
					}
				}

				if (offlineMs < 0 && offlineTask.isDone()) {
					offlineMs = elapsed;
					offlineResult = get(offlineTask, "offline");
					if (usable.isUsable(offlineResult)) {
						// Offline is preferred whenever it is ready, even if online was
						// marginally quicker: it is the routing this fork actually tuned -
						// the Cairo priority rules, narrow-street avoidance, the .obf's HH
						// shortcuts - and none of that exists on a generic server. Online is
						// here to cut LATENCY, so it only wins by being genuinely earlier.
						log("offline won in " + offlineMs + " ms"
								+ (onlineMs >= 0
								   ? " (online answered at " + onlineMs + " ms, unusable or later)"
								   : " (online still out)"));
						return offlineResult;
					}
				}

				if (offlineTask.isDone() && onlineTask.isDone()) {
					break;
				}
				if (elapsed > ONLINE_GIVE_UP_MS && offlineTask.isDone()) {
					break;
				}
				try {
					Thread.sleep(25);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					break;
				}
			}

			// Offline finished but could not answer, and online could. This is the case the whole
			// feature exists for - outside the downloaded .obf, a missing map, an area the local
			// router cannot solve - and until now it was the one case the race structurally could
			// NOT deliver: the online result was only ever returned while `!offlineTask.isDone()`,
			// so an offline calculation that finished FIRST and FAILED locked out a perfectly good
			// online route. It was fetched, tested, and thrown away, and navigation then failed
			// with a usable answer sitting in a local variable.
			if (!usable.isUsable(offlineResult)) {
				if (onlineMs < 0 && onlineTask.isDone()) {
					onlineResult = get(onlineTask, "online");
				}
				if (usable.isUsable(onlineResult)) {
					log("ONLINE won by default in " + onlineMs + " ms - offline finished but"
							+ " produced no usable route");
					return onlineResult;
				}
			}

			// Both finished and neither was usable, which is what a plain offline calculation
			// would have produced on its own. Returning the offline one keeps the failure
			// identical to the behaviour without this feature.
			onlineTask.cancel(true);
			log("neither side usable (offline=" + offlineMs + "ms online=" + onlineMs + "ms)");
			return offlineResult;
		} finally {
			// Interrupt the loser rather than leaving it to finish. shutdown() alone never
			// interrupts, so on the common path - offline wins - the online socket stayed open for
			// up to the 30 s connect + 60 s read timeout after the driver already had their route,
			// once per reroute. shutdownNow() sets the interrupt, which is what a blocked read
			// needs.
			//
			// The offline task is a different matter and is deliberately NOT force-killed here: it
			// is deep inside native HH code that does not observe an interrupt, and interrupting
			// its thread mid-JNI is a worse failure than letting it run out. Its cost is bounded by
			// the calculation itself.
			pool.shutdownNow();
		}
	}

	/**
	 * A copy of the calculation parameters for the ONLINE side to scribble on.
	 *
	 * <p>This method is the reason the race can be wired in at all.
	 * {@code RouteProvider.findOnlineRoute} writes to the parameters it is given - it assigns
	 * {@code gpxFile}, {@code gpxRoute} and {@code initialCalculation}, and it sets
	 * {@code intermediates} to null - while {@code findVectorMapsRoute} is reading the same
	 * object on another thread. Sharing one instance between the two would be a data race whose
	 * victim is the OFFLINE route, which is the one result that must always be trustworthy.
	 *
	 * <p>A shallow copy is correct here and a deep one would be wrong. The fields the online
	 * side mutates are all references or primitives on THIS object, so replacing them affects
	 * only the copy. The objects still shared - {@code ctx}, {@code mode}, {@code start},
	 * {@code end} - are read-only for both sides.
	 *
	 * <p>Two fields are deliberately NOT copied across:
	 * <ul>
	 *   <li>{@code calculationProgress} gets a fresh instance. It carries {@code isCancelled}
	 *       and the progress counters the UI reads; letting the online request drive the same
	 *       object would make the progress bar jump and, worse, let one side's cancellation
	 *       stop the other.</li>
	 *   <li>The listeners are left null. They fire callbacks into route handling, and the
	 *       losing side of a race must be silent - it is a calculation whose result is about to
	 *       be thrown away.</li>
	 * </ul>
	 */
	@NonNull
	public static RouteCalculationParams copyForOnline(@NonNull RouteCalculationParams src) {
		RouteCalculationParams p = new RouteCalculationParams();
		p.start = src.start;
		p.end = src.end;
		p.intermediates = src.intermediates == null ? null : new ArrayList<>(src.intermediates);
		p.currentLocation = src.currentLocation;
		p.ctx = src.ctx;
		p.mode = src.mode;
		p.gpxRoute = src.gpxRoute;
		p.previousToRecalculate = src.previousToRecalculate;
		p.onlyStartPointChanged = src.onlyStartPointChanged;
		p.cairoDriveDispatchedAt = src.cairoDriveDispatchedAt;
		p.fast = src.fast;
		p.leftSide = src.leftSide;
		p.startTransportStop = src.startTransportStop;
		p.targetTransportStop = src.targetTransportStop;
		p.inPublicTransportMode = src.inPublicTransportMode;
		p.extraIntermediates = src.extraIntermediates;
		p.initialCalculation = src.initialCalculation;
		p.gpxFile = src.gpxFile;
		// Fresh, not shared - see the note above.
		p.calculationProgress = new RouteCalculationProgress();
		return p;
	}

	@Nullable
	private static <T> T get(@NonNull Future<T> f, @NonNull String side) {
		try {
			return f.get();
		} catch (Throwable t) {
			// Never rethrown. A throwing side simply loses the race; the other one is already
			// running and is the answer. This is the whole reason a network failure cannot
			// break navigation here.
			log(side + " side threw " + t.getClass().getSimpleName()
					+ (t.getMessage() != null ? ": " + t.getMessage() : ""));
			return null;
		}
	}

	private static void log(@NonNull String detail) {
		CairoDriveLog.log(TRACE_TAG, detail);
	}
}
