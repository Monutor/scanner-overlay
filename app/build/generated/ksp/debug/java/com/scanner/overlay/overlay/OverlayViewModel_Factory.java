package com.scanner.overlay.overlay;

import android.content.SharedPreferences;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata
@QualifierMetadata
@DaggerGenerated
@Generated(
    value = "dagger.internal.codegen.ComponentProcessor",
    comments = "https://dagger.dev"
)
@SuppressWarnings({
    "unchecked",
    "rawtypes",
    "KotlinInternal",
    "KotlinInternalInJava",
    "cast",
    "deprecation"
})
public final class OverlayViewModel_Factory implements Factory<OverlayViewModel> {
  private final Provider<SharedPreferences> prefsProvider;

  public OverlayViewModel_Factory(Provider<SharedPreferences> prefsProvider) {
    this.prefsProvider = prefsProvider;
  }

  @Override
  public OverlayViewModel get() {
    return newInstance(prefsProvider.get());
  }

  public static OverlayViewModel_Factory create(Provider<SharedPreferences> prefsProvider) {
    return new OverlayViewModel_Factory(prefsProvider);
  }

  public static OverlayViewModel newInstance(SharedPreferences prefs) {
    return new OverlayViewModel(prefs);
  }
}
