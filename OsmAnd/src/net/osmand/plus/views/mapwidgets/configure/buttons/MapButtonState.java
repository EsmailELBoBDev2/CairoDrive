package net.osmand.plus.views.mapwidgets.configure.buttons;

import static net.osmand.plus.quickaction.ButtonAppearanceParams.BIG_SIZE_DP;
import static net.osmand.plus.quickaction.ButtonAppearanceParams.ORIGINAL_VALUE;
import static net.osmand.plus.quickaction.ButtonAppearanceParams.ROUND_RADIUS_DP;
import static net.osmand.plus.quickaction.ButtonAppearanceParams.TRANSPARENT_ALPHA;

import android.content.Context;
import android.graphics.drawable.Drawable;

import androidx.annotation.ColorInt;
import androidx.annotation.DrawableRes;
import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiContext;

import net.osmand.StateChangedListener;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.helpers.AndroidUiHelper;
import net.osmand.plus.quickaction.ButtonAppearanceParams;
import net.osmand.plus.quickaction.MapButtonsHelper;
import net.osmand.plus.render.RenderingIcons;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.plus.settings.backend.OsmandSettings;
import net.osmand.plus.settings.backend.preferences.CommonPreference;
import net.osmand.plus.utils.AndroidUtils;
import net.osmand.plus.utils.UiUtilities;
import net.osmand.shared.grid.ButtonPositionSize;
import net.osmand.util.Algorithms;

import java.util.ArrayList;
import java.util.List;

public abstract class MapButtonState {

	protected final OsmandApplication app;
	protected final OsmandSettings settings;
	protected final UiUtilities uiUtilities;

	protected final String id;

	protected final List<CommonPreference<?>> allPreferences;
	protected final CommonPreference<String> iconPref;
	protected final CommonPreference<Integer> sizePref;
	protected final CommonPreference<Float> opacityPref;
	protected final CommonPreference<Integer> cornerRadiusPref;
	protected final CommonPreference<Long> portraitPositionPref;
	protected final CommonPreference<Long> landscapePositionPref;
	protected final ButtonPositionSize positionSize;
	protected final ButtonPositionSize defaultPositionSize;

	private final StateChangedListener<Integer> sizeListener;

	protected boolean portrait;

	public MapButtonState(@NonNull OsmandApplication app, @NonNull String id) {
		this.id = id;
		this.app = app;
		this.settings = app.getSettings();
		this.uiUtilities = app.getUIUtilities();
		this.allPreferences = new ArrayList<>();

		this.iconPref = addPreference(settings.registerStringPreference(id + "_icon", null)).makeProfile().cache();
		this.sizePref = addPreference(settings.registerIntPreference(id + "_size", ORIGINAL_VALUE)).makeProfile().cache();
		this.opacityPref = addPreference(settings.registerFloatPreference(id + "_opacity", ORIGINAL_VALUE)).makeProfile().cache();
		this.cornerRadiusPref = addPreference(settings.registerIntPreference(id + "_corner_radius", ORIGINAL_VALUE)).makeProfile().cache();
		this.portraitPositionPref = addPreference(settings.registerLongPreference(id + "_position_portrait", ORIGINAL_VALUE)).makeProfile().cache();
		this.landscapePositionPref = addPreference(settings.registerLongPreference(id + "_position_landscape", ORIGINAL_VALUE)).makeProfile().cache();
		this.positionSize = setupButtonPosition(new ButtonPositionSize(getId()));
		this.defaultPositionSize = setupButtonPosition(new ButtonPositionSize(getId()));

		sizeListener = change -> {
			updatePosition(positionSize);
			updatePosition(defaultPositionSize);
		};
		sizePref.addListener(sizeListener);
	}

	@NonNull
	public String getId() {
		return id;
	}

	@NonNull
	public abstract String getName();

	@NonNull
	public abstract String getDescription();

	public abstract boolean isEnabled();

	/**
	 * P11/8. Writes the four values into {@code into}, or allocates when it is null.
	 *
	 * <p><b>This is deliberately not a cache, and the distinction is the whole point.</b> A cache
	 * hands the SAME instance to every caller, and that breaks two things here: this class returns
	 * a mutable object that {@code QuickActionButtonState} rewrites, and
	 * {@code DefaultButtonsAppearanceFragment} calls this twice specifically to hold a current and
	 * an original copy to diff - aliasing those would silently kill change detection.
	 *
	 * <p>Passing a buffer inverts the ownership instead. The caller supplies storage it alone
	 * owns, so nothing is shared and every existing caller that passes nothing behaves exactly as
	 * before. Only {@code MapButton}, which draws every button every frame, opts in - and it
	 * carries two buffers rather than one, because it compares this frame's values against the
	 * ones it kept from last frame. One buffer would compare equal to itself forever and the
	 * button would stop redrawing.
	 */
	@NonNull
	private static ButtonAppearanceParams applyTo(@Nullable ButtonAppearanceParams into,
	                                              @Nullable String iconName, int size,
	                                              float opacity, int cornerRadius) {
		if (into == null) {
			return new ButtonAppearanceParams(iconName, size, opacity, cornerRadius);
		}
		into.setIconName(iconName);
		into.setSize(size);
		into.setOpacity(opacity);
		into.setCornerRadius(cornerRadius);
		return into;
	}

	@NonNull
	public ButtonAppearanceParams createDefaultAppearanceParams(@Nullable Boolean nightMode) {
		return createDefaultAppearanceParams(nightMode, null);
	}

	/**
	 * P11/8. As above, but fills {@code into} when the caller owns a buffer to write into.
	 *
	 * <p>See {@link #applyTo} for why this exists and why it is opt-in rather than a cache.
	 */
	@NonNull
	public ButtonAppearanceParams createDefaultAppearanceParams(@Nullable Boolean nightMode,
	                                                            @Nullable ButtonAppearanceParams into) {
		MapButtonsHelper buttonsHelper = app.getMapButtonsHelper();
		int size = buttonsHelper.getDefaultSizePref().get();
		if (size <= 0) {
			size = getDefaultSize();
		}
		float opacity = buttonsHelper.getDefaultOpacityPref().get();
		if (opacity < 0) {
			opacity = getDefaultOpacity();
		}
		int cornerRadius = buttonsHelper.getDefaultCornerRadiusPref().get();
		if (cornerRadius < 0) {
			cornerRadius = getDefaultCornerRadius();
		}
		return applyTo(into, getDefaultIconName(nightMode), size, opacity, cornerRadius);
	}

	@LayoutRes
	public abstract int getDefaultLayoutId();

	@NonNull
	public abstract String getDefaultIconName(@Nullable Boolean nightMode);

	public int getDefaultSize() {
		return BIG_SIZE_DP;
	}

	public float getDefaultOpacity() {
		return TRANSPARENT_ALPHA;
	}

	public int getDefaultCornerRadius() {
		return ROUND_RADIUS_DP;
	}

	@Nullable
	public String getSavedIconName() {
		return iconPref.get();
	}

	@NonNull
	public CommonPreference<String> getIconPref() {
		return iconPref;
	}

	@NonNull
	public CommonPreference<Integer> getSizePref() {
		return sizePref;
	}

	@NonNull
	public CommonPreference<Float> getOpacityPref() {
		return opacityPref;
	}

	@NonNull
	public CommonPreference<Integer> getCornerRadiusPref() {
		return cornerRadiusPref;
	}

	@NonNull
	public abstract CommonPreference getVisibilityPref();

	@NonNull
	public ButtonPositionSize getPositionSize() {
		return positionSize;
	}

	@NonNull
	public ButtonPositionSize getDefaultPositionSize() {
		ButtonPositionSize position = setupButtonPosition(defaultPositionSize);
		updatePosition(position);
		return position;
	}

	@NonNull
	/**
	 * P11/8. One allocation per call instead of two.
	 *
	 * <p>This built a whole {@code ButtonAppearanceParams} purely to read four fallback values out
	 * of it and then threw it away, on every visible button on every frame. The fallbacks are read
	 * directly now, so the only object created is the one actually returned.
	 *
	 * <p><b>The remaining allocation cannot be cached, and the reason is worth recording so nobody
	 * tries.</b> {@code ButtonAppearanceParams} is a Kotlin class of {@code var} fields, and
	 * {@code QuickActionButtonState.createAppearanceParams} mutates the instance this returns.
	 * {@code DefaultButtonsAppearanceFragment} also calls this twice specifically to hold a
	 * current and an original copy to compare - handing both callers the same cached object would
	 * alias those into one and silently break change detection. The earlier note that this was
	 * "already bounded by an early return" was the wrong reason for the right conclusion.
	 */
	public ButtonAppearanceParams createAppearanceParams(@Nullable Boolean nightMode) {
		return createAppearanceParams(nightMode, null);
	}

	/**
	 * P11/8. As above, but writes into a caller-owned buffer when one is supplied.
	 *
	 * @param into a buffer the CALLER owns exclusively, or null to allocate as before
	 */
	@NonNull
	public ButtonAppearanceParams createAppearanceParams(@Nullable Boolean nightMode,
	                                                      @Nullable ButtonAppearanceParams into) {
		MapButtonsHelper buttonsHelper = app.getMapButtonsHelper();

		String iconName = getSavedIconName();
		if (Algorithms.isEmpty(iconName)) {
			iconName = getDefaultIconName(nightMode);
		}
		int size = sizePref.get();
		if (size <= 0) {
			size = buttonsHelper.getDefaultSizePref().get();
			if (size <= 0) {
				size = getDefaultSize();
			}
		}
		float opacity = opacityPref.get();
		if (opacity < 0) {
			opacity = buttonsHelper.getDefaultOpacityPref().get();
			if (opacity < 0) {
				opacity = getDefaultOpacity();
			}
		}
		int cornerRadius = cornerRadiusPref.get();
		if (cornerRadius < 0) {
			cornerRadius = buttonsHelper.getDefaultCornerRadiusPref().get();
			if (cornerRadius < 0) {
				cornerRadius = getDefaultCornerRadius();
			}
		}
		return applyTo(into, iconName, size, opacity, cornerRadius);
	}

	@NonNull
	protected abstract ButtonPositionSize setupButtonPosition(@NonNull ButtonPositionSize position);

	@NonNull
	protected ButtonPositionSize setupButtonPosition(@NonNull ButtonPositionSize position,
	                                                 int posH, int posV, boolean xMove, boolean yMove) {
		position.setPosH(posH);
		position.setPosV(posV);
		position.setXMove(xMove);
		position.setYMove(yMove);
		position.setMarginX(0);
		position.setMarginY(0);

		return position;
	}

	public void updatePositions(@NonNull @UiContext Context context) {
		this.portrait = AndroidUiHelper.isOrientationPortrait(context);

		updatePosition(positionSize);
		updatePosition(defaultPositionSize);
	}

	public void savePosition() {
		ButtonPositionSize positionSize = getPositionSize();
		CommonPreference<Long> preference = portrait ? portraitPositionPref : landscapePositionPref;
		preference.set(positionSize.toLongValue());
	}

	protected void updatePosition(@NonNull ButtonPositionSize position) {
		CommonPreference<Long> preference = portrait ? portraitPositionPref : landscapePositionPref;
		Long value = preference.get();
		if (value != null && value > 0) {
			position.fromLongValue(value);
		}
		int size = createAppearanceParams(null).getSize();
		size = (size / 8) + 1;
		position.setSize(size, size);
	}

	@Nullable
	public Drawable getIcon(@ColorInt int color, boolean nightMode, boolean mapIcon) {
		int iconId = getIconId(nightMode);
		return iconId != 0 ? getIcon(iconId, color, nightMode, mapIcon) : null;
	}

	@DrawableRes
	public int getIconId(boolean nightMode) {
		String iconName = createAppearanceParams(nightMode).getIconName();
		int iconId = AndroidUtils.getDrawableId(app, iconName);
		return iconId != 0 ? iconId : RenderingIcons.getBigIconResourceId(iconName);
	}

	@Nullable
	public Drawable getIcon(@DrawableRes int iconId, @ColorInt int color, boolean nightMode, boolean mapIcon) {
		return color != 0 ? uiUtilities.getPaintedIcon(iconId, color) : uiUtilities.getIcon(iconId);
	}

	public void resetToDefault(@NonNull ApplicationMode appMode) {
		iconPref.resetModeToDefault(appMode);
		sizePref.resetModeToDefault(appMode);
		opacityPref.resetModeToDefault(appMode);
		cornerRadiusPref.resetModeToDefault(appMode);
		portraitPositionPref.resetModeToDefault(appMode);
		landscapePositionPref.resetModeToDefault(appMode);
		getVisibilityPref().resetModeToDefault(appMode);
	}

	public void copyForMode(@NonNull ApplicationMode fromMode, @NonNull ApplicationMode toMode) {
		iconPref.setModeValue(toMode, iconPref.getModeValue(fromMode));
		sizePref.setModeValue(toMode, sizePref.getModeValue(fromMode));
		opacityPref.setModeValue(toMode, opacityPref.getModeValue(fromMode));
		cornerRadiusPref.setModeValue(toMode, cornerRadiusPref.getModeValue(fromMode));
		portraitPositionPref.setModeValue(toMode, portraitPositionPref.getModeValue(fromMode));
		landscapePositionPref.setModeValue(toMode, landscapePositionPref.getModeValue(fromMode));
		getVisibilityPref().setModeValue(toMode, getVisibilityPref().getModeValue(fromMode));
	}

	public void onButtonStateRemoved() {
		settings.removePreferences(allPreferences);
	}

	@NonNull
	protected <T> CommonPreference<T> addPreference(@NonNull CommonPreference<T> preference) {
		allPreferences.add(preference);
		return preference;
	}

	public boolean hasCustomAppearance() {
		return !Algorithms.objectEquals(createAppearanceParams(null), createDefaultAppearanceParams(null));
	}

	@NonNull
	@Override
	public String toString() {
		return getId();
	}
}