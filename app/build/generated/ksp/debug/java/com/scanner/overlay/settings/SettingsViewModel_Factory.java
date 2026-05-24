package com.scanner.overlay.settings;

import android.app.Application;
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
public final class SettingsViewModel_Factory implements Factory<SettingsViewModel> {
  private final Provider<Application> appProvider;

  private final Provider<SharedPreferences> prefsProvider;

  public SettingsViewModel_Factory(Provider<Application> appProvider,
      Provider<SharedPreferences> prefsProvider) {
    this.appProvider = appProvider;
    this.prefsProvider = prefsProvider;
  }

  @Override
  public SettingsViewModel get() {
    return newInstance(appProvider.get(), prefsProvider.get());
  }

  public static SettingsViewModel_Factory create(Provider<Application> appProvider,
      Provider<SharedPreferences> prefsProvider) {
    return new SettingsViewModel_Factory(appProvider, prefsProvider);
  }

  public static SettingsViewModel newInstance(Application app, SharedPreferences prefs) {
    return new SettingsViewModel(app, prefs);
  }
}
