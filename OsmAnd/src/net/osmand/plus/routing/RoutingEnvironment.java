package net.osmand.plus.routing;

import net.osmand.router.PrecalculatedRouteDirection;
import net.osmand.router.RoutePlannerFrontEnd;
import net.osmand.router.RoutingContext;

public class RoutingEnvironment {

	private final RoutingContext ctx;
	private final RoutingContext complexCtx;
	private final RoutePlannerFrontEnd router;
	private final PrecalculatedRouteDirection precalculated;

	/**
	 * Non-null when this environment came from - or is a candidate for - {@code RouteProvider}'s warm routing
	 * environment cache. The provider hands it back on completion, which is what returns the cache slot; see
	 * {@code RouteProvider.finishWarmSession}.
	 */
	private RouteProvider.WarmRoutingEnvironment warmEnvironment;

	public RoutingEnvironment(RoutePlannerFrontEnd router, RoutingContext ctx, RoutingContext complexCtx, PrecalculatedRouteDirection precalculated) {
		this.router = router;
		this.ctx = ctx;
		this.complexCtx = complexCtx;
		this.precalculated = precalculated;
	}

	void setWarmEnvironment(RouteProvider.WarmRoutingEnvironment warmEnvironment) {
		this.warmEnvironment = warmEnvironment;
	}

	RouteProvider.WarmRoutingEnvironment getWarmEnvironment() {
		return warmEnvironment;
	}

	public RoutePlannerFrontEnd getRouter() {
		return router;
	}

	public RoutingContext getCtx() {
		return ctx;
	}

	public RoutingContext getComplexCtx() {
		return complexCtx;
	}

	public PrecalculatedRouteDirection getPrecalculated() {
		return precalculated;
	}
}
